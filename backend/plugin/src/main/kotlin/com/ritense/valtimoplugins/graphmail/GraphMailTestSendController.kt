package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val RATE_LIMIT_INTERVAL_MS = 10_000L  // max 1 test-send per 10s per user

@RestController
@RequestMapping("/api/v1/plugin/entra")
class GraphMailTestSendController(
    private val graphMailClient: GraphMailClient,
    // PluginService hydrates the plugin instance with decrypted @PluginProperty(secret=true) values.
    // Reading clientSecret directly from PluginConfigurationRepository would yield AES ciphertext,
    // not the actual secret — causing every test-send to fail with a misleading 401.
    private val pluginService: PluginService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val logger = LoggerFactory.getLogger(GraphMailTestSendController::class.java)

    private val rateLimitStore = ConcurrentHashMap<String, AtomicLong>()

    // CAS-based check — atomically read and update in one step.
    private fun isRateLimited(username: String): Boolean {
        val now = System.currentTimeMillis()
        val tracker = rateLimitStore.computeIfAbsent(username) { AtomicLong(0) }
        val prev = tracker.get()
        if (now - prev < RATE_LIMIT_INTERVAL_MS) return true
        return !tracker.compareAndSet(prev, now)
    }

    // Admin-only: this endpoint sends real email using production credentials.
    // Access is enforced at the HTTP security layer via GraphMailHttpSecurityConfigurer.
    @PostMapping("/test-send")
    fun testSend(
        @RequestBody request: GraphMailTestSendRequest,
        authentication: Authentication,
    ): ResponseEntity<GraphMailTestSendResponse> {
        if (!isValidUuid(request.pluginConfigurationId)) {
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(false, "Ongeldig pluginConfigurationId — verwacht UUID-formaat", 400)
            )
        }
        if (!isValidEmail(request.recipient)) {
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(false, "Ongeldig ontvanger e-mailadres", 400)
            )
        }

        if (isRateLimited(authentication.name)) {
            logger.warn("Test send rate limited — user: {}", authentication.name)
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                GraphMailTestSendResponse(false, "Te veel verzoeken — wacht 10 seconden voor de volgende testmail", 429)
            )
        }

        val configIdStr = request.pluginConfigurationId
        val plugin: GraphMailPlugin? = try {
            pluginService.createInstance(PluginConfigurationId.existingId(UUID.fromString(configIdStr))) as? GraphMailPlugin
        } catch (ex: Exception) {
            logger.warn("Plugin configuration not found or failed to load for id: {}", configIdStr, ex)
            null
        }
        if (plugin == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                GraphMailTestSendResponse(false, "Plugin configuratie niet gevonden", 404)
            )
        }

        // The frontend always sends senderMailbox from the test-send form.
        // plugin.testSenderMailbox (stored as a @PluginProperty) acts as a fallback
        // so direct API callers or future integrations can omit the field.
        val testSender = request.senderMailbox.trim()
            .ifBlank { plugin.testSenderMailbox?.trim() ?: "" }

        if (testSender.isEmpty() || !isValidEmail(testSender)) {
            logger.warn("Test send rejected — invalid senderMailbox in request")
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(
                    false,
                    "Ongeldig afzender e-mailadres — vul een geldig e-mailadres in als afzender",
                    400
                )
            )
        }

        // The sender allowlist applies to test sends too — an admin must not be able to
        // send as an arbitrary tenant mailbox either. Empty list = deny-all (strict).
        val allowlist = plugin.allowedSendersList()
        if (allowlist.isEmpty()) {
            logger.warn("Test send rejected — 'allowedSenders' is not configured for plugin configuration {}", configIdStr)
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(
                    false,
                    "De pluginconfiguratie heeft geen 'allowedSenders' (afzender-whitelist) — " +
                        "vul de toegestane afzendermailboxen in en sla de configuratie op",
                    400
                )
            )
        }
        if (!isSenderAllowed(testSender, allowlist)) {
            logger.warn("Test send rejected — sender {} not on the allowedSenders allowlist", maskEmail(testSender))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                GraphMailTestSendResponse(
                    false,
                    "Afzender staat niet op de 'allowedSenders' whitelist van deze pluginconfiguratie",
                    403
                )
            )
        }

        logger.info(
            "Test send requested — recipient: {}, mailbox: {}",
            maskEmail(request.recipient), maskEmail(testSender)
        )

        val sendStart = System.currentTimeMillis()
        return try {
            graphMailClient.sendMail(
                credentials = GraphCredentials(
                    tenantId = plugin.tenantId, clientId = plugin.clientId, clientSecret = plugin.clientSecret,
                ),
                mail = OutboundMail(
                    senderMailbox = testSender,
                    toRecipients = listOf(GraphRecipient(GraphEmailAddress(address = request.recipient))),
                    subject = "Testmail — Microsoft Graph Mail Plugin",
                    bodyHtml = buildTestMailBody(testSender),
                    saveToSentItems = false,
                ),
            )
            val durationMs = System.currentTimeMillis() - sendStart
            logger.info("Test send successful — recipient: {}", maskEmail(request.recipient))
            eventPublisher.publishEvent(
                GraphMailEmailSentEvent(
                    senderMailbox = maskEmail(testSender),
                    recipientCount = 1,
                    ccCount = 0,
                    bccCount = 0,
                    attachmentCount = 0,
                    durationMs = durationMs,
                )
            )
            ResponseEntity.ok(
                GraphMailTestSendResponse(
                    success = true,
                    message = "Testmail succesvol verzonden naar ${request.recipient}",
                    statusCode = 202,
                )
            )
        } catch (ex: GraphMailTokenExpiredException) {
            val message = "Authenticatie mislukt (401) — token geweigerd door Graph API, controleer Client Secret"
            logger.warn("Test send failed — {}", message)
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GraphMailTestSendResponse(false, message, 401))
        } catch (ex: Exception) {
            val rawStatus = when (ex) {
                is GraphMailException      -> ex.statusCode
                is HttpClientErrorException  -> ex.statusCode.value()
                is HttpServerErrorException  -> ex.statusCode.value()
                else                         -> 500
            }
            val statusCode = if (rawStatus in 100..599) rawStatus else 500
            val message = when (statusCode) {
                400  -> "Ongeldige aanvraag (400) — controleer Tenant ID en Client ID"
                401  -> "Authenticatie mislukt (401) — controleer Tenant ID, Client ID en Client Secret"
                403  -> "Toegang geweigerd (403) — controleer of Mail.Send is toegekend in de Azure App Registration"
                429  -> "Te veel verzoeken (429) — probeer het over een moment opnieuw"
                503, 502, 504 -> "Azure / Graph API tijdelijk niet beschikbaar ($statusCode) — probeer het later opnieuw"
                else -> "Fout $statusCode: ${ex.message ?: "Onbekende fout"}"
            }
            logger.warn("Test send failed — status: {}", statusCode, ex)
            ResponseEntity.status(statusCode)
                .body(GraphMailTestSendResponse(false, message, statusCode))
        }
    }

}
