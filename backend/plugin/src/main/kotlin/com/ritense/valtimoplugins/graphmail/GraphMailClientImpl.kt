package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
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
) : GraphMailClient {
    private val logger = LoggerFactory.getLogger(GraphMailClientImpl::class.java)

    private fun cacheKey(
        tenantId: String,
        clientId: String,
    ) = "$tenantId:$clientId"

    internal fun getAccessToken(credentials: GraphCredentials): String {
        require(credentials.tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(credentials.clientId.isNotBlank()) { "clientId must not be blank" }
        require(credentials.clientSecret.isNotBlank()) { "clientSecret must not be blank" }

        val key = cacheKey(credentials.tenantId, credentials.clientId)
        return tokenCache.getOrFetch(key) { fetchToken(credentials) }
    }

    override fun invalidateCache(
        tenantId: String?,
        clientId: String?,
    ) {
        when {
            tenantId != null && clientId != null -> {
                val removed = tokenCache.invalidate(cacheKey(tenantId, clientId))
                if (removed) logger.warn("Token cache cleared for [{}:***]", tenantId)
            }
            tenantId == null && clientId == null -> {
                val count = tokenCache.invalidateAll()
                logger.warn("Token cache fully cleared ({} entries)", count)
            }
            else ->
                logger.warn(
                    "invalidateCache called with partial selector — ignored (tenantId={}, clientId={})",
                    tenantId != null,
                    clientId != null,
                )
        }
    }

    private fun fetchToken(credentials: GraphCredentials): Pair<String, Instant> {
        val (tenantId, clientId, clientSecret) = credentials
        val url =
            UriComponentsBuilder
                .fromUriString("$tokenBaseUrl/{tenantId}/oauth2/v2.0/token")
                .build()
                .expand(tenantId)
                .toUriString()

        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "client_credentials")
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("scope", GRAPH_SCOPE)
            }

        val response = postTokenWithRetry(url, form, tenantId)

        // Guard against negative TTL if Azure returns expires_in < buffer.
        val ttl = (response.expiresIn.toLong() - TOKEN_EXPIRY_BUFFER_SECONDS).coerceAtLeast(0L)
        val expiresAt = Instant.now().plusSeconds(ttl)

        logger.info("New token acquired [{}:***] — valid for {}s", tenantId, ttl)
        return response.accessToken to expiresAt
    }

    private fun postTokenWithRetry(
        url: String,
        form: LinkedMultiValueMap<String, String>,
        tenantId: String,
    ): TokenResponse {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            attempt++
            try {
                return restClient
                    .post()
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
                throw GraphMailException(
                    "Azure rejected token request (${ex.statusCode}) — check Client ID and Secret",
                    statusCode = ex.statusCode.value(),
                )
            } catch (ex: HttpServerErrorException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.error("Azure Entra unavailable ({}) after {} token attempts", ex.statusCode, attempt)
                    throw GraphMailException("Azure Entra unavailable (${ex.statusCode})")
                }
                val delay = backoffMs + Random.nextLong(0, (backoffMs / 2).coerceAtLeast(1))
                logger.warn(
                    "Token request {} — attempt {}/{}, retrying in {}ms",
                    ex.statusCode,
                    attempt,
                    TOKEN_MAX_RETRIES,
                    delay,
                )
                Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: ResourceAccessException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.warn(
                        "Token request timed out for tenant [{}] after {} attempts: {}",
                        tenantId,
                        attempt,
                        ex.message,
                    )
                    throw GraphMailException("Could not reach Azure Entra (timeout or network error): ${ex.message}")
                }
                val delay = backoffMs + Random.nextLong(0, (backoffMs / 2).coerceAtLeast(1))
                logger.warn(
                    "Token request network error — attempt {}/{}, retrying in {}ms: {}",
                    attempt,
                    TOKEN_MAX_RETRIES,
                    delay,
                    ex.message,
                )
                Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
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

    override fun sendMail(
        credentials: GraphCredentials,
        mail: OutboundMail,
    ) {
        require(mail.toRecipients.isNotEmpty()) { "At least one recipient is required" }

        val recipientCount = mail.toRecipients.size
        logger.info(
            "Sending email — recipients: {}, mailbox: '{}'",
            recipientCount,
            maskEmail(mail.senderMailbox),
        )

        val useDraftFlow =
            mail.attachments.any { it.sizeBytes > INLINE_ATTACHMENT_THRESHOLD_BYTES } ||
                mail.attachments.sumOf { it.sizeBytes } > INLINE_ATTACHMENT_THRESHOLD_BYTES

        if (useDraftFlow) {
            logger.debug(
                "Using draft+upload flow — {} attachment(s), total {} bytes",
                mail.attachments.size,
                mail.attachments.sumOf { it.sizeBytes },
            )
            sendViaDraftAndUpload(credentials, mail)
        } else {
            val sendMailUri: URI =
                UriComponentsBuilder
                    .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/sendMail")
                    .buildAndExpand(mail.senderMailbox)
                    .toUri()
            val payload = buildInlinePayload(mail)
            sendWithRefreshAndRetry(credentials, sendMailUri, payload, mail.senderMailbox)
        }
        logger.info("Email sent successfully — recipients: {}", recipientCount)
    }

    private fun buildInlinePayload(mail: OutboundMail): SendMailRequest {
        val inlineAttachments =
            mail.attachments.map { a ->
                GraphAttachment(
                    name = a.name,
                    contentType = a.contentType,
                    contentBytes = Base64.getEncoder().encodeToString(a.rawBytes),
                )
            }
        return SendMailRequest(
            message =
                GraphMessage(
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

    private fun sendViaDraftAndUpload(
        credentials: GraphCredentials,
        mail: OutboundMail,
    ) {
        val deadline = System.currentTimeMillis() + MAX_DRAFT_SEND_WALL_CLOCK_MS

        val draftMessage =
            GraphMessage(
                subject = mail.subject,
                body = GraphBody(contentType = GRAPH_BODY_CONTENT_TYPE_HTML, content = mail.bodyHtml),
                toRecipients = mail.toRecipients,
                ccRecipients = mail.ccRecipients,
                bccRecipients = mail.bccRecipients,
                replyTo = mail.replyToRecipients,
            )
        val draftId = createDraftWithRetry(credentials, mail.senderMailbox, draftMessage, deadline)
        logger.debug("Draft created id={}", draftId)

        try {
            for (attachment in mail.attachments) {
                val uploadUrl = createUploadSession(credentials, mail.senderMailbox, draftId, attachment, deadline)
                uploadInChunks(uploadUrl, attachment, deadline)
                logger.debug("Attachment uploaded: name='{}' size={}", attachment.name, attachment.sizeBytes)
            }
            sendDraftWithRetry(credentials, mail.senderMailbox, draftId, deadline)
        } catch (ex: Exception) {
            deleteDraftBestEffort(credentials, mail.senderMailbox, draftId)
            throw ex
        }
    }

    private fun deleteDraftBestEffort(
        credentials: GraphCredentials,
        senderMailbox: String,
        draftId: String,
    ) {
        try {
            val uri: URI =
                UriComponentsBuilder
                    .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}")
                    .buildAndExpand(senderMailbox, draftId)
                    .toUri()
            val token = getAccessToken(credentials)
            restClient
                .delete()
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
        val uri: URI =
            UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages")
                .buildAndExpand(senderMailbox)
                .toUri()

        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw GraphMailException("Draft creation timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
            }
            attempt++
            val token = getAccessToken(credentials)
            try {
                return restClient
                    .post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(DraftMessageResponse::class.java)
                    ?.id
                    ?: throw GraphMailException("Empty response body when creating draft message")
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) {
                            throw GraphMailTokenExpiredException(
                                "Token rejected when creating draft (401) — check Mail.ReadWrite permission",
                                ex,
                            )
                        }
                        invalidateCache(credentials.tenantId, credentials.clientId)
                        tokenRefreshed = true
                        attempt--
                    }
                    429 -> {
                        if (attempt >= MAX_RETRIES) {
                            throw GraphMailException("Rate limited creating draft after $MAX_RETRIES attempts", ex)
                        }
                        val wait =
                            (
                                parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                                    .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000
                            ).coerceAtMost(deadline - System.currentTimeMillis())
                        if (wait > 0) Thread.sleep(wait)
                    }
                    else -> throw GraphMailException(
                        "Graph API rejected draft creation (${ex.statusCode})",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES) {
                    throw GraphMailException("Graph API unavailable creating draft after $MAX_RETRIES attempts", ex)
                }
                val delay =
                    (backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    private fun createUploadSession(
        credentials: GraphCredentials,
        senderMailbox: String,
        draftId: String,
        attachment: ResolvedAttachment,
        deadline: Long,
    ): String {
        val uri: URI =
            UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/attachments/createUploadSession")
                .buildAndExpand(senderMailbox, draftId)
                .toUri()

        val body =
            CreateUploadSessionRequest(
                attachmentItem =
                    UploadAttachmentItem(
                        name = attachment.name,
                        size = attachment.sizeBytes,
                        contentType = attachment.contentType,
                    ),
            )

        if (System.currentTimeMillis() > deadline) {
            throw GraphMailException("Upload session creation timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
        }

        var tokenRefreshed = false

        fun doPost(token: String): String =
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

        val uploadUrl =
            try {
                doPost(getAccessToken(credentials))
            } catch (ex: HttpClientErrorException) {
                if (ex.statusCode.value() == 401 && !tokenRefreshed) {
                    tokenRefreshed = true
                    invalidateCache(credentials.tenantId, credentials.clientId)
                    doPost(getAccessToken(credentials))
                } else {
                    throw GraphMailException(
                        "Graph API rejected upload session creation (${ex.statusCode})",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                }
            }

        // Derive expected scheme+host from graphBaseUrl so WireMock tests (http://localhost)
        // pass while production rejects any non-Microsoft https:// domain.
        val expectedScheme =
            runCatching {
                java.net.URI
                    .create(graphBaseUrl)
                    .scheme
            }.getOrElse { "https" }
        val expectedHost =
            runCatching {
                java.net.URI
                    .create(graphBaseUrl)
                    .host
            }.getOrElse { "graph.microsoft.com" }
        val actualScheme =
            runCatching {
                java.net.URI
                    .create(uploadUrl)
                    .scheme
            }.getOrNull()
        val actualHost =
            runCatching {
                java.net.URI
                    .create(uploadUrl)
                    .host
            }.getOrNull()
        val microsoftHosts = listOf(".microsoft.com", ".office.com", ".office.net", ".office365.com")
        require(
            actualScheme == expectedScheme &&
                (actualHost == expectedHost || microsoftHosts.any { actualHost?.endsWith(it) == true }),
        ) {
            "Upload URL from Graph API failed domain validation"
        }
        return uploadUrl
    }

    private fun uploadInChunks(
        uploadUrl: String,
        attachment: ResolvedAttachment,
        deadline: Long,
    ) {
        val bytes = attachment.rawBytes
        val total = bytes.size.toLong()
        var offset = 0L

        while (offset < total) {
            if (System.currentTimeMillis() > deadline) {
                throw GraphMailException("Attachment upload timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
            }

            val end = minOf(offset + UPLOAD_CHUNK_BYTES - 1, total - 1)
            val chunkLen = (end - offset + 1).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), (end + 1).toInt())

            var chunkAttempt = 0
            var success = false
            while (!success) {
                chunkAttempt++
                try {
                    restClient
                        .put()
                        .uri(URI.create(uploadUrl))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(chunkLen.toLong())
                        .header("Content-Range", "bytes $offset-$end/$total")
                        .body(chunk)
                        .retrieve()
                        .toBodilessEntity()
                    success = true
                } catch (ex: HttpClientErrorException) {
                    throw GraphMailException(
                        "Chunk upload rejected (${ex.statusCode}) at offset $offset",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                } catch (ex: HttpServerErrorException) {
                    if (chunkAttempt >= CHUNK_MAX_RETRIES) {
                        throw GraphMailException(
                            "Chunk upload failed after $CHUNK_MAX_RETRIES attempts at offset $offset",
                            ex,
                        )
                    }
                    val delay =
                        (500L * (1 shl (chunkAttempt - 1)))
                            .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                } catch (ex: ResourceAccessException) {
                    if (chunkAttempt >= CHUNK_MAX_RETRIES) {
                        throw GraphMailException(
                            "Chunk upload unreachable after $CHUNK_MAX_RETRIES attempts at offset $offset",
                            ex,
                        )
                    }
                    val delay =
                        (500L * (1 shl (chunkAttempt - 1)))
                            .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                }
            }
            offset = end + 1
        }
    }

    private fun sendDraftWithRetry(
        credentials: GraphCredentials,
        senderMailbox: String,
        draftId: String,
        deadline: Long,
    ) {
        val uri: URI =
            UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/send")
                .buildAndExpand(senderMailbox, draftId)
                .toUri()

        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw GraphMailException("Draft send timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
            }
            attempt++
            val token = getAccessToken(credentials)
            try {
                restClient
                    .post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentLength(0)
                    .retrieve()
                    .toBodilessEntity()
                return
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) {
                            throw GraphMailTokenExpiredException(
                                "Token rejected sending draft (401) — check Mail.Send permission",
                                ex,
                            )
                        }
                        invalidateCache(credentials.tenantId, credentials.clientId)
                        tokenRefreshed = true
                        attempt--
                    }
                    429 -> {
                        if (attempt >= MAX_RETRIES) {
                            throw GraphMailException("Rate limited sending draft after $MAX_RETRIES attempts", ex)
                        }
                        val wait =
                            (
                                parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                                    .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000
                            ).coerceAtMost(deadline - System.currentTimeMillis())
                        if (wait > 0) Thread.sleep(wait)
                    }
                    else -> throw GraphMailException(
                        "Graph API rejected draft send (${ex.statusCode})",
                        ex,
                        statusCode = ex.statusCode.value(),
                    )
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES) {
                    throw GraphMailException("Graph API unavailable sending draft after $MAX_RETRIES attempts", ex)
                }
                val delay =
                    (backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    private fun sendWithRefreshAndRetry(
        credentials: GraphCredentials,
        url: URI,
        payload: SendMailRequest,
        mailbox: String,
    ) {
        val callDeadline = System.currentTimeMillis() + MAX_SEND_WALL_CLOCK_MS
        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > callDeadline) {
                throw GraphMailException(
                    "Email send timed out — total wall-clock limit of ${MAX_SEND_WALL_CLOCK_MS}ms exceeded",
                )
            }
            attempt++
            val token = getAccessToken(credentials)
            try {
                restClient
                    .post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
                return
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) {
                            logger.error("401 Unauthorized after fresh token — Mail.Send permission likely missing")
                            throw GraphMailTokenExpiredException(
                                "Token rejected by Graph API (401) even after refresh — check Mail.Send permission",
                                ex,
                            )
                        }
                        logger.warn(
                            "401 Unauthorized — invalidating cached token for [{}:***] and retrying once",
                            credentials.tenantId,
                        )
                        invalidateCache(credentials.tenantId, credentials.clientId)
                        tokenRefreshed = true
                        attempt-- // Don't burn a retry attempt on the refresh.
                    }
                    429 -> {
                        val retryAfter =
                            parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                                .coerceAtMost(MAX_RETRY_AFTER_SECONDS)
                        if (attempt >= MAX_RETRIES) {
                            throw GraphMailException("Rate limited after $MAX_RETRIES attempts (429)", ex)
                        }
                        val sleepMs = minOf(retryAfter * 1000, callDeadline - System.currentTimeMillis())
                        logger.warn(
                            "429 Rate limited — waiting {}ms (Retry-After={}s, wall-clock cap)",
                            sleepMs,
                            retryAfter,
                        )
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                    }
                    else -> {
                        logger.error(
                            "Graph API rejected email ({}): mailbox='{}'",
                            ex.statusCode,
                            maskEmail(mailbox),
                        )
                        throw GraphMailException(
                            "Graph API rejected email (${ex.statusCode}): check mailbox and Mail.Send permission",
                            ex,
                            statusCode = ex.statusCode.value(),
                        )
                    }
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES) {
                    logger.error("Graph API unavailable after {} attempts ({})", MAX_RETRIES, ex.statusCode)
                    throw GraphMailException(
                        "Graph API unavailable after $MAX_RETRIES attempts (${ex.statusCode})",
                        ex,
                    )
                }
                // Jitter scales with backoff (up to 20% of current backoff) — better thundering-herd defense.
                val jitter = Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1))
                val delayMs = minOf(backoffMs + jitter, callDeadline - System.currentTimeMillis())
                logger.warn(
                    "Graph API {} — attempt {}/{}, waiting {}ms",
                    ex.statusCode,
                    attempt,
                    MAX_RETRIES,
                    delayMs,
                )
                if (delayMs > 0) Thread.sleep(delayMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: ResourceAccessException) {
                if (attempt >= MAX_RETRIES) {
                    logger.error("Graph API unreachable after {} attempts: {}", MAX_RETRIES, ex.message)
                    throw GraphMailException("Graph API unreachable after $MAX_RETRIES attempts: ${ex.message}", ex)
                }
                val delayMs =
                    minOf(
                        backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)),
                        callDeadline - System.currentTimeMillis(),
                    )
                logger.warn(
                    "Graph API network error — attempt {}/{}, retrying in {}ms",
                    attempt,
                    MAX_RETRIES,
                    delayMs,
                )
                if (delayMs > 0) Thread.sleep(delayMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }
}
