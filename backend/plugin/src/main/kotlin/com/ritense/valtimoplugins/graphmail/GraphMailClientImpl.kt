package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.random.Random

private const val GRAPH_SCOPE = "https://graph.microsoft.com/.default"
private const val TOKEN_EXPIRY_BUFFER_SECONDS = 60L
private const val MAX_RETRIES = 5
private const val INITIAL_BACKOFF_MS = 500L
private const val BACKOFF_MULTIPLIER = 2.0
// Cap the Retry-After header to limit per-sleep blocking time on the job-executor thread.
// 15s × 5 retries = 75s worst case per send; wall-clock caps (30s/120s) apply on top.
private const val MAX_RETRY_AFTER_SECONDS = 15L
private const val TOKEN_MAX_RETRIES = 3
// Hard wall-clock cap for the entire send operation (including all retries and backoff sleeps).
// Ensures a 429 storm cannot hold an Operaton BPM job-executor thread longer than this limit.
private const val MAX_SEND_WALL_CLOCK_MS = 30_000L
// Longer deadline for the draft+upload flow — large uploads can take tens of seconds.
private const val MAX_DRAFT_SEND_WALL_CLOCK_MS = 120_000L
private const val CHUNK_MAX_RETRIES = 3

// How many consecutive server responses may decline to advance the upload before giving up.
// Graph is allowed to ask us to go back and re-send a range; it is not allowed to do so forever.
private const val MAX_UPLOAD_STALLS = 3

// 4xx statuses that no amount of retrying will change: they need a configuration, permission or
// input fix first. Everything else in the 4xx range is treated as possibly transient so the job
// executor keeps its normal retry behaviour.
private val PERMANENT_CLIENT_ERROR_STATUSES = setOf(400, 403, 404, 405, 409, 413, 422)

// Hosts Graph legitimately hands back for an attachment upload session.
private val MICROSOFT_UPLOAD_HOST_SUFFIXES =
    listOf(".microsoft.com", ".office.com", ".office.net", ".office365.com", ".sharepoint.com")

// NOTE (threading): retry backoff uses Thread.sleep(), which blocks the calling thread.
// In Operaton BPM (V13), SERVICE_TASK actions run on the job-executor thread pool.
// Worst case: 429 with Retry-After=15s × 5 attempts = 75s; wall-clock caps enforce the hard limit.
// Size the job executor thread pool accordingly (operaton.bpm.job-executor.core-pool-size),
// or replace with a non-blocking HTTP client (WebClient) in a future release.

/**
 * Implementation of [GraphMailClient].
 *
 * - OAuth2 Client Credentials, per-(tenantId+clientId) cache via the shared [GraphTokenCache]
 *   (see that class for why the cache must be injected rather than owned by this instance)
 * - Exponential backoff with jitter on send (5 attempts) and on token fetch (3)
 * - 429 honours Retry-After (capped at 15s to limit job-executor thread blocking)
 * - 401 invalidates only the affected key, then retries exactly once
 * - PII-aware logging (mailbox + recipients are masked)
 */
class GraphMailClientImpl(
    private val restClient: RestClient,
    private val tokenBaseUrl: String = "https://login.microsoftonline.com",
    private val graphBaseUrl: String = "https://graph.microsoft.com",
    private val tokenCache: GraphTokenCache = GraphTokenCache(),
    // Whether an upload URL handed back by the API must live on a Microsoft host. True whenever
    // graphBaseUrl is a real Graph endpoint (see GraphMailHttpProperties.isProductionGraphEndpoint);
    // false only for a WireMock or sandbox endpoint, which legitimately returns its own host.
    //
    // This used to be inferred inline by comparing the upload URL against graphBaseUrl, which made
    // the check exactly as strong as whatever an administrator had typed into the graphBaseUrl
    // plugin property. That property is gone; the decision now comes from validated deployment
    // configuration instead.
    private val requireMicrosoftUploadHost: Boolean = true,
) : GraphMailClient {

    private val logger = LoggerFactory.getLogger(GraphMailClientImpl::class.java)

    // A hash of clientSecret is part of the cache key — not just tenantId+clientId — so a
    // plugin configuration with a wrong or stale secret can never ride on a token that a
    // *different* (correct) secret already fetched and cached for the same tenant/client.
    // Without this, tenantId+clientId (not marked `secret` on the plugin property, so visible
    // in the admin UI) would be enough to obtain a working token via the shared cache without
    // Azure Entra ever validating the caller's actual secret.
    private fun cachePrefix(tenantId: String, clientId: String) = "$tenantId:$clientId:"

    private fun cacheKey(tenantId: String, clientId: String, clientSecret: String) =
        "${cachePrefix(tenantId, clientId)}${sha256(clientSecret)}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun cacheKeyFor(credentials: GraphCredentials) =
        cacheKey(credentials.tenantId, credentials.clientId, credentials.clientSecret)

    // `deadline` is the send operation's wall-clock budget. Passing it down matters: without it the
    // token retry loop runs its own unbounded Thread.sleep() cycle *inside* the send loop, and the
    // send's "hard" cap silently becomes cap + token-retry time.
    internal fun getAccessToken(credentials: GraphCredentials, deadline: Long? = null): String {
        requireCredentials(credentials)
        return tokenCache.getOrFetch(cacheKeyFor(credentials)) { fetchToken(credentials, deadline) }
    }

    // Bypasses the cache entirely. Used only by the 401 handler — see the comment at that call site
    // for why re-reading the cache after an invalidation is not enough.
    private fun forceFreshAccessToken(credentials: GraphCredentials, deadline: Long?): String {
        requireCredentials(credentials)
        return tokenCache.forceFetch(cacheKeyFor(credentials)) { fetchToken(credentials, deadline) }
    }

    private fun requireCredentials(credentials: GraphCredentials) {
        require(credentials.tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(credentials.clientId.isNotBlank()) { "clientId must not be blank" }
        require(credentials.clientSecret.isNotBlank()) { "clientSecret must not be blank" }
    }

    override fun invalidateCache(tenantId: String?, clientId: String?) {
        when {
            tenantId != null && clientId != null -> {
                // The secret isn't known here (this is the public invalidate-by-selector API),
                // so drop every cached entry for this tenant/client regardless of which secret
                // variant fetched it.
                val removed = tokenCache.invalidateByPrefix(cachePrefix(tenantId, clientId))
                if (removed > 0) logger.warn("Token cache cleared ({} entries) for [{}:***]", removed, tenantId)
            }
            tenantId == null && clientId == null -> {
                val count = tokenCache.invalidateAll()
                logger.warn("Token cache fully cleared ({} entries)", count)
            }
            else -> logger.warn(
                "invalidateCache called with partial selector — ignored (tenantId={}, clientId={})",
                tenantId != null, clientId != null
            )
        }
    }

    private fun fetchToken(credentials: GraphCredentials, deadline: Long? = null): Pair<String, Instant> {
        val (tenantId, clientId, clientSecret) = credentials
        val url = UriComponentsBuilder
            .fromUriString("$tokenBaseUrl/{tenantId}/oauth2/v2.0/token")
            .build()
            .expand(tenantId)
            .toUriString()

        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("scope", GRAPH_SCOPE)
        }

        val response = postTokenWithRetry(url, form, tenantId, deadline)

        // Guard against negative TTL if Azure returns expires_in < buffer.
        val ttl = (response.expiresIn.toLong() - TOKEN_EXPIRY_BUFFER_SECONDS).coerceAtLeast(0L)
        val expiresAt = Instant.now().plusSeconds(ttl)

        logger.info("New token acquired [{}:***] — valid for {}s", tenantId, ttl)
        return response.accessToken to expiresAt
    }

    // Jitter widens as backoffMs grows; divisor controls how wide (backoffMs / divisor).
    // Token requests use a wider jitter band (divisor 2) than Graph API calls (divisor 5) —
    // preserved from the pre-existing tuning, not changed by this refactor.
    // Never sleep past the operation's deadline; a null deadline means "no budget attached".
    private fun boundedByDeadline(delayMs: Long, deadline: Long?): Long =
        if (deadline == null) delayMs else delayMs.coerceAtMost(deadline - System.currentTimeMillis())

    private fun jitteredBackoff(
        backoffMs: Long,
        divisor: Long,
    ): Long = backoffMs + Random.nextLong(0, (backoffMs / divisor).coerceAtLeast(1))

    private fun postTokenWithRetry(
        url: String,
        form: LinkedMultiValueMap<String, String>,
        tenantId: String,
        deadline: Long?,
    ): TokenResponse {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            if (deadline != null && System.currentTimeMillis() > deadline) {
                throw GraphMailRetryableException(
                    "Timed out acquiring an access token for tenant [$tenantId] before the send deadline"
                )
            }
            attempt++
            try {
                return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
                    ?: throw GraphMailException("Empty response when fetching access token")
            } catch (ex: HttpClientErrorException) {
                logger.error("Token request rejected ({}) for tenant [{}]", ex.statusCode, tenantId)
                // Do NOT log ex.responseBodyAsString — Azure token responses may echo back the
                // client_secret. The HTTP status (logged above) is sufficient for diagnosis.
                throw GraphMailPermanentException(
                    "Azure rejected token request (${ex.statusCode}) — check tenantId, clientId and " +
                        "clientSecret of this plugin configuration, and that the app registration is " +
                        "not expired or disabled",
                    ex,
                    statusCode = ex.statusCode.value(),
                )
            } catch (ex: HttpServerErrorException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.error("Azure Entra unavailable ({}) after {} token attempts", ex.statusCode, attempt)
                    throw GraphMailRetryableException(
                        "Azure Entra unavailable (${ex.statusCode}) after $attempt token attempts",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                }
                val delay = boundedByDeadline(jitteredBackoff(backoffMs, divisor = 2), deadline)
                logger.warn("Token request {} — attempt {}/{}, retrying in {}ms",
                    ex.statusCode, attempt, TOKEN_MAX_RETRIES, delay)
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: RestClientException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.warn("Token request timed out for tenant [{}] after {} attempts: {}",
                        tenantId, attempt, ex.message)
                    throw GraphMailRetryableException(
                        "Could not reach Azure Entra (timeout or network error) after $attempt attempts: ${ex.message}",
                        ex,
                    )
                }
                val delay = boundedByDeadline(jitteredBackoff(backoffMs, divisor = 2), deadline)
                logger.warn("Token request network error — attempt {}/{}, retrying in {}ms: {}",
                    attempt, TOKEN_MAX_RETRIES, delay, ex.message)
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    // Shared retry loop for every credential-bearing Graph API call (draft creation, upload
    // session creation, draft send, inline send): deadline-aware, refreshes the cached token
    // exactly once on 401, honours Retry-After on 429 (capped), and backs off with jitter on
    // 5xx/network errors.
    //
    // Consolidates what were four near-identical hand-rolled retry loops. Several inconsistencies
    // this also fixes: (1) draft-creation and draft-send previously did NOT retry on
    // connection/transport failures at all — a network blip there was a hard failure, while the
    // same blip during inline send was retried; (2) upload-session creation only ever retried a
    // 401 once and never retried 429/5xx/network errors at all, and on a second 401 threw a plain
    // GraphMailException instead of GraphMailTokenExpiredException like the other three flows;
    // (3) the network-error catch was narrowed to ResourceAccessException everywhere, but
    // RestClient can also wrap a connection reset that happens mid-response-read as a bare
    // RestClientException (thrown from readWithMessageConverters, not doExecute) — catching the
    // broader RestClientException here (after the more specific Http*ErrorException catches)
    // covers both cases without swallowing HTTP status errors, which are matched first.
    private fun <T> sendGraphRequestWithRetry(
        credentials: GraphCredentials,
        mailbox: String,
        deadline: Long,
        timeoutBudgetMs: Long,
        actionLabel: String,
        permissionHint: String,
        // Whether a transport-level failure (connection reset, read timeout) may be retried.
        // A transport failure says nothing about whether the server processed the request, so for a
        // non-idempotent POST — sendMail, messages/{id}/send — a retry is a coin flip that costs the
        // recipient a duplicate message when it lands wrong. Only set this for steps that are
        // provably repeatable: draft creation and upload-session creation.
        retryOnTransportError: Boolean,
        request: (token: String) -> T,
    ): T {
        var tokenRefreshed = false
        var forceFreshToken = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw GraphMailRetryableException(
                    "Timed out during $actionLabel after ${timeoutBudgetMs}ms"
                )
            }
            attempt++
            val token =
                if (forceFreshToken) {
                    forceFreshToken = false
                    forceFreshAccessToken(credentials, deadline)
                } else {
                    getAccessToken(credentials, deadline)
                }
            try {
                return request(token)
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) {
                            logger.error(
                                "401 Unauthorized after fresh token during {} — {} likely missing",
                                actionLabel,
                                permissionHint,
                            )
                            throw GraphMailTokenExpiredException(
                                "Token rejected during $actionLabel (401) even after refresh — check $permissionHint",
                                ex,
                            )
                        }
                        logger.warn(
                            "401 Unauthorized during {} — invalidating the rejected token for [{}:***] " +
                                "and retrying once with a freshly fetched one",
                            actionLabel,
                            credentials.tenantId,
                        )
                        // Drop the entry only while it still holds the token that was just refused:
                        // another thread may already have refreshed it, and throwing that away would
                        // cost every caller of this key an extra Azure round-trip.
                        tokenCache.invalidateIfMatches(cacheKeyFor(credentials), token)
                        // Then bypass the cache on the retry. Invalidate-and-re-read is not enough:
                        // a concurrent caller that entered fetch() before the invalidation can write
                        // the very same rejected token back, and we would retry with a token already
                        // known to be refused — then report it as a missing permission.
                        forceFreshToken = true
                        tokenRefreshed = true
                        attempt-- // Don't burn a retry attempt on the refresh.
                    }
                    429 -> {
                        if (attempt >= MAX_RETRIES) {
                            // Persistent throttling is transient by nature: hand it back to the job
                            // executor, which reschedules without holding a thread.
                            throw GraphMailRetryableException(
                                "Rate limited during $actionLabel after $MAX_RETRIES attempts (429) — " +
                                    "Graph is throttling this mailbox; the job executor will retry",
                                ex,
                                statusCode = 429,
                            )
                        }
                        val retryAfterMs =
                            parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                                .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000
                        val wait = retryAfterMs.coerceAtMost(deadline - System.currentTimeMillis())
                        logger.warn("429 Rate limited during {} — waiting {}ms", actionLabel, wait)
                        if (wait > 0) Thread.sleep(wait)
                    }
                    else -> {
                        val status = ex.statusCode.value()
                        val permanent = status in PERMANENT_CLIENT_ERROR_STATUSES
                        val remedy = remedyFor(status, permissionHint)
                        logger.error(
                            "Graph API rejected {} ({}) — {}: mailbox='{}'. {}",
                            actionLabel,
                            ex.statusCode,
                            if (permanent) "PERMANENT, not retried" else "possibly transient",
                            maskEmail(mailbox),
                            remedy,
                        )
                        val message =
                            "Graph API rejected $actionLabel (${ex.statusCode}). $remedy"
                        throw if (permanent) {
                            GraphMailPermanentException(message, ex, statusCode = status)
                        } else {
                            GraphMailRetryableException(message, ex, statusCode = status)
                        }
                    }
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES) {
                    logger.error(
                        "Graph API unavailable during {} after {} attempts ({})",
                        actionLabel,
                        MAX_RETRIES,
                        ex.statusCode,
                    )
                    throw GraphMailRetryableException(
                        "Graph API unavailable during $actionLabel after $MAX_RETRIES attempts (${ex.statusCode})",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                }
                val delay = jitteredBackoff(backoffMs, divisor = 5).coerceAtMost(deadline - System.currentTimeMillis())
                logger.warn(
                    "Graph API {} during {} — attempt {}/{}, waiting {}ms",
                    ex.statusCode,
                    actionLabel,
                    attempt,
                    MAX_RETRIES,
                    delay,
                )
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: RestClientException) {
                if (!retryOnTransportError) {
                    logger.error(
                        "Transport failure during {} — NOT retried: the request may already have been " +
                            "accepted by Graph, and a blind retry would deliver a duplicate message. " +
                            "Verify the mailbox before re-running this activity. Cause: {}",
                        actionLabel,
                        ex.message,
                    )
                    throw GraphMailUnknownOutcomeException(
                        "Transport failure during $actionLabel — delivery outcome is UNKNOWN and was " +
                            "deliberately not retried to avoid sending a duplicate: ${ex.message}",
                        ex,
                    )
                }
                if (attempt >= MAX_RETRIES) {
                    logger.error(
                        "Graph API unreachable during {} after {} attempts: {}",
                        actionLabel,
                        MAX_RETRIES,
                        ex.message,
                    )
                    throw GraphMailRetryableException(
                        "Graph API unreachable during $actionLabel after $MAX_RETRIES attempts: ${ex.message}",
                        ex,
                    )
                }
                val delay = jitteredBackoff(backoffMs, divisor = 5).coerceAtMost(deadline - System.currentTimeMillis())
                logger.warn(
                    "Graph API network error during {} — attempt {}/{}, retrying in {}ms",
                    actionLabel,
                    attempt,
                    MAX_RETRIES,
                    delay,
                )
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    // Turns a bare status code into something an administrator reading the GZAC logs can act on,
    // instead of "Graph API rejected email send (403)" with no indication of what to change.
    private fun remedyFor(status: Int, permissionHint: String): String = when (status) {
        400 -> "Graph rejected the request payload — check the sender mailbox, recipient addresses " +
            "and subject for values Graph considers malformed."
        403 -> "Access denied — grant $permissionHint as an *application* permission on the Azure app " +
            "registration and give it admin consent. If the permission is already granted, check " +
            "whether an Exchange Online Application Access Policy excludes this mailbox."
        404 -> "Mailbox not found — verify the sender mailbox exists in this tenant and is licensed " +
            "for Exchange Online."
        413 -> "Payload too large — reduce the attachment size or count."
        422 -> "Graph could not process the message content — check the sanitised HTML body."
        else -> "Check the mailbox and $permissionHint."
    }

    // Retry-After can be seconds ("120") or an HTTP date ("Wed, 21 Oct 2025 07:28:00 GMT").
    private fun parseRetryAfter(header: String?): Long {
        if (header == null) return 5L
        header.toLongOrNull()?.let { return it }
        return runCatching {
            val target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            (target.toEpochSecond() - Instant.now().epochSecond).coerceAtLeast(0L)
        }.getOrElse {
            logger.warn("Unparseable Retry-After header '{}' — defaulting to 5s", header)
            5L
        }
    }

    override fun sendMail(credentials: GraphCredentials, mail: OutboundMail) {
        require(mail.toRecipients.isNotEmpty()) { "At least one recipient is required" }

        val recipientCount = mail.toRecipients.size
        logger.info("Sending email — recipients: {}, mailbox: '{}'",
            recipientCount, maskEmail(mail.senderMailbox))

        // The total already covers the single-attachment case, so a separate `any { > threshold }`
        // check would be redundant.
        val totalAttachmentBytes = mail.attachments.sumOf { it.sizeBytes }
        val useDraftFlow = totalAttachmentBytes > INLINE_ATTACHMENT_THRESHOLD_BYTES

        if (useDraftFlow) {
            logger.debug("Using draft+upload flow — {} attachment(s), total {} bytes",
                mail.attachments.size, totalAttachmentBytes)
            sendViaDraftAndUpload(credentials, mail)
        } else {
            val sendMailUri: URI = UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/sendMail")
                .buildAndExpand(mail.senderMailbox)
                .toUri()
            val payload = buildInlinePayload(mail)
            sendWithRefreshAndRetry(credentials, sendMailUri, payload, mail.senderMailbox)
        }
        logger.info("Email sent successfully — recipients: {}", recipientCount)
    }

    private fun buildInlinePayload(mail: OutboundMail): SendMailRequest {
        val inlineAttachments = mail.attachments.map { a ->
            GraphAttachment(
                name = a.name,
                contentType = a.contentType,
                contentBytes = Base64.getEncoder().encodeToString(a.rawBytes),
            )
        }
        return SendMailRequest(
            message = GraphMessage(
                subject = mail.subject,
                body = GraphBody(contentType = GRAPH_BODY_CONTENT_TYPE_HTML, content = mail.bodyHtml),
                toRecipients = mail.toRecipients,
                ccRecipients = mail.ccRecipients,
                bccRecipients = mail.bccRecipients,
                replyTo = mail.replyToRecipients,
                attachments = inlineAttachments,
            ),
            saveToSentItems = mail.saveToSentItems,
        )
    }

    private fun sendViaDraftAndUpload(credentials: GraphCredentials, mail: OutboundMail) {
        val deadline = System.currentTimeMillis() + MAX_DRAFT_SEND_WALL_CLOCK_MS

        val draftMessage = GraphMessage(
            subject = mail.subject,
            body = GraphBody(contentType = GRAPH_BODY_CONTENT_TYPE_HTML, content = mail.bodyHtml),
            toRecipients = mail.toRecipients,
            ccRecipients = mail.ccRecipients,
            bccRecipients = mail.bccRecipients,
            replyTo = mail.replyToRecipients,
        )
        val draftId = createDraftWithRetry(credentials, mail.senderMailbox, draftMessage, deadline)
        logger.debug("Draft created id={}", draftId)

        // Cleanup deliberately covers the upload phase only. Once /send has been called we can no
        // longer be sure the message is still a draft: a send that succeeded but timed out on the
        // response has already moved this id to Sent Items, and deleting it there would destroy the
        // record of a message the recipient actually received.
        try {
            for (attachment in mail.attachments) {
                val uploadUrl = createUploadSession(credentials, mail.senderMailbox, draftId, attachment, deadline)
                uploadInChunks(uploadUrl, attachment, deadline)
                logger.debug("Attachment uploaded: name='{}' size={}", attachment.name, attachment.sizeBytes)
            }
        } catch (ex: Exception) {
            deleteDraftBestEffort(credentials, mail.senderMailbox, draftId)
            throw ex
        }

        sendDraftWithRetry(credentials, mail.senderMailbox, draftId, deadline)
    }

    private fun deleteDraftBestEffort(credentials: GraphCredentials, senderMailbox: String, draftId: String) {
        try {
            val uri: URI = UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}")
                .buildAndExpand(senderMailbox, draftId)
                .toUri()
            val token = getAccessToken(credentials)
            restClient.delete()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toBodilessEntity()
            logger.debug("Orphaned draft deleted id={}", draftId)
        } catch (ex: Exception) {
            logger.warn("Failed to delete orphaned draft id={}: {}", draftId, ex.message)
        }
    }

    private fun createDraftWithRetry(
        credentials: GraphCredentials,
        senderMailbox: String,
        message: GraphMessage,
        deadline: Long,
    ): String {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages")
            .buildAndExpand(senderMailbox)
            .toUri()

        return sendGraphRequestWithRetry(
            credentials,
            senderMailbox,
            deadline,
            MAX_DRAFT_SEND_WALL_CLOCK_MS,
            actionLabel = "draft creation",
            permissionHint = "Mail.ReadWrite permission",
            // Safe to retry: a duplicate draft is invisible to the recipient, and an orphan is
            // cleaned up by deleteDraftBestEffort.
            retryOnTransportError = true,
        ) { token ->
            restClient
                .post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                .body(DraftMessageResponse::class.java)
                ?.id
                ?: throw GraphMailException("Empty response body when creating draft message")
        }
    }

    private fun createUploadSession(
        credentials: GraphCredentials,
        senderMailbox: String,
        draftId: String,
        attachment: ResolvedAttachment,
        deadline: Long,
    ): String {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/attachments/createUploadSession")
            .buildAndExpand(senderMailbox, draftId)
            .toUri()

        val body = CreateUploadSessionRequest(
            attachmentItem = UploadAttachmentItem(
                name = attachment.name,
                size = attachment.sizeBytes,
                contentType = attachment.contentType,
            )
        )

        val uploadUrl =
            sendGraphRequestWithRetry(
                credentials,
                senderMailbox,
                deadline,
                MAX_DRAFT_SEND_WALL_CLOCK_MS,
                actionLabel = "upload session creation",
                permissionHint = "Mail.ReadWrite permission",
                // Safe to retry: creating a second upload session has no user-visible effect.
                retryOnTransportError = true,
            ) { token ->
                restClient
                    .post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(UploadSessionResponse::class.java)
                    ?.uploadUrl
                    ?: throw GraphMailException("Empty uploadUrl in upload session response")
            }

        validateUploadUrl(uploadUrl)
        return uploadUrl
    }

    // The upload URL is attacker-influenceable in the sense that it arrives over the network, and
    // the subsequent PUT carries attachment content — so it is validated before anything is sent to
    // it. Note the URL is pre-authenticated by Graph and deliberately carries no bearer token, which
    // is exactly why its host matters.
    private fun validateUploadUrl(uploadUrl: String) {
        val uri = runCatching { java.net.URI.create(uploadUrl) }.getOrNull()
        val host = uri?.host
        val valid = if (requireMicrosoftUploadHost) {
            uri?.scheme == "https" && host != null &&
                MICROSOFT_UPLOAD_HOST_SUFFIXES.any { host == it.removePrefix(".") || host.endsWith(it) }
        } else {
            // Sandbox/test endpoint: accept only the very host we are already talking to, never an
            // arbitrary third one.
            val expected = runCatching { java.net.URI.create(graphBaseUrl) }.getOrNull()
            uri?.scheme == expected?.scheme && host != null && host == expected?.host
        }
        // The rejected host is deliberately kept out of the message: it comes from an external
        // response and this string ends up in logs and, indirectly, in operator-facing errors.
        require(valid) { "Upload URL returned by the Graph API failed host validation" }
    }

    private fun uploadInChunks(uploadUrl: String, attachment: ResolvedAttachment, deadline: Long) {
        val bytes = attachment.rawBytes
        val total = bytes.size.toLong()
        var offset = 0L
        var stalls = 0

        while (offset < total) {
            if (System.currentTimeMillis() > deadline)
                throw GraphMailRetryableException(
                    "Attachment upload timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")

            val end = minOf(offset + UPLOAD_CHUNK_BYTES - 1, total - 1)
            val chunkLen = (end - offset + 1).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), (end + 1).toInt())

            var chunkAttempt = 0
            var nextOffset: Long? = null
            while (nextOffset == null) {
                chunkAttempt++
                try {
                    val response = restClient.put()
                        .uri(URI.create(uploadUrl))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(chunkLen.toLong())
                        .header("Content-Range", "bytes $offset-$end/$total")
                        .body(chunk)
                        .retrieve()
                        .toEntity(UploadChunkResponse::class.java)
                    // Let the server decide where to continue. Graph reports what it actually holds
                    // in nextExpectedRanges; blindly advancing our own offset after a retry can put
                    // the client out of step with the server and corrupt the attachment.
                    nextOffset = response.body
                        ?.nextExpectedRanges
                        ?.firstOrNull()
                        ?.substringBefore('-')
                        ?.trim()
                        ?.toLongOrNull()
                        ?: (end + 1)
                } catch (ex: HttpClientErrorException) {
                    // An upload session is served by the same throttling layer as every other Graph
                    // endpoint, so a 429 here deserves the same treatment it gets everywhere else in
                    // this client. Failing hard on it throws away the whole upload — and the draft —
                    // over a condition that clears by itself in seconds.
                    if (ex.statusCode.value() == 429 && chunkAttempt < CHUNK_MAX_RETRIES) {
                        val wait = (parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                            .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000)
                            .coerceAtMost(deadline - System.currentTimeMillis())
                        logger.warn(
                            "429 during chunk upload of '{}' at offset {} — attempt {}/{}, waiting {}ms",
                            attachment.name, offset, chunkAttempt, CHUNK_MAX_RETRIES, wait,
                        )
                        if (wait > 0) Thread.sleep(wait)
                        continue
                    }
                    val status = ex.statusCode.value()
                    val message =
                        "Chunk upload of '${attachment.name}' rejected ($status) at offset $offset of $total"
                    throw if (status == 429) {
                        GraphMailRetryableException(
                            "$message after $CHUNK_MAX_RETRIES attempts", ex, statusCode = status)
                    } else {
                        GraphMailPermanentException(message, ex, statusCode = status)
                    }
                } catch (ex: HttpServerErrorException) {
                    if (chunkAttempt >= CHUNK_MAX_RETRIES)
                        throw GraphMailRetryableException(
                            "Chunk upload of '${attachment.name}' failed after $CHUNK_MAX_RETRIES " +
                                "attempts at offset $offset (${ex.statusCode})",
                            ex, statusCode = ex.statusCode.value())
                    val delay = (500L * (1 shl (chunkAttempt - 1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                } catch (ex: RestClientException) {
                    // A chunk PUT is idempotent — the same byte range can safely be re-sent — so
                    // unlike the send calls this transport retry cannot duplicate anything.
                    if (chunkAttempt >= CHUNK_MAX_RETRIES)
                        throw GraphMailRetryableException(
                            "Chunk upload of '${attachment.name}' unreachable after " +
                                "$CHUNK_MAX_RETRIES attempts at offset $offset",
                            ex)
                    val delay = (500L * (1 shl (chunkAttempt - 1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                }
            }
            // Graph's reported range is authoritative, including when it points *backwards*: that
            // means it did not commit the range we just sent, and continuing from end + 1 would
            // leave a hole in the attachment. So follow it wherever it goes, and bound how often it
            // may decline to advance instead — a rewind is legitimate, an endless rewind is not.
            if (nextOffset !in 0..total) {
                throw GraphMailRetryableException(
                    "Graph reported an out-of-range nextExpectedRanges offset ($nextOffset) for " +
                        "'${attachment.name}' of $total bytes — aborting rather than uploading a " +
                        "corrupt attachment",
                )
            }
            if (nextOffset > offset) {
                stalls = 0
            } else {
                stalls++
                if (stalls > MAX_UPLOAD_STALLS) {
                    throw GraphMailRetryableException(
                        "Attachment upload of '${attachment.name}' made no progress: Graph asked to " +
                            "resume at offset $nextOffset $MAX_UPLOAD_STALLS times in a row while " +
                            "uploading from offset $offset of $total",
                    )
                }
                logger.warn(
                    "Graph asked to resume the upload of '{}' at offset {} instead of advancing " +
                        "past {} — re-sending from there ({}/{})",
                    attachment.name, nextOffset, end, stalls, MAX_UPLOAD_STALLS,
                )
            }
            offset = nextOffset
        }
    }

    private fun sendDraftWithRetry(
        credentials: GraphCredentials,
        senderMailbox: String,
        draftId: String,
        deadline: Long,
    ) {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/send")
            .buildAndExpand(senderMailbox, draftId)
            .toUri()

        sendGraphRequestWithRetry(
            credentials,
            senderMailbox,
            deadline,
            MAX_DRAFT_SEND_WALL_CLOCK_MS,
            actionLabel = "draft send",
            permissionHint = "Mail.Send permission",
            // NOT safe to retry: the draft may already have been accepted for delivery.
            retryOnTransportError = false,
        ) { token ->
            restClient
                .post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentLength(0)
                .retrieve()
                .toBodilessEntity()
        }
    }

    private fun sendWithRefreshAndRetry(
        credentials: GraphCredentials,
        url: URI,
        payload: SendMailRequest,
        mailbox: String,
    ) {
        val deadline = System.currentTimeMillis() + MAX_SEND_WALL_CLOCK_MS
        sendGraphRequestWithRetry(
            credentials,
            mailbox,
            deadline,
            MAX_SEND_WALL_CLOCK_MS,
            actionLabel = "email send",
            permissionHint = "Mail.Send permission",
            // NOT safe to retry: Graph may already have queued the message for delivery.
            retryOnTransportError = false,
        ) { token ->
            restClient
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
        }
    }
}
