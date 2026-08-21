package com.ritense.valtimoplugins.graphmail

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

// Azure Entra and Graph endpoints per cloud instance. Commercial is what a Dutch government
// deployment uses, but hard-coding only that would silently break a sovereign-cloud tenant.
private val TOKEN_HOSTS =
    setOf(
        "login.microsoftonline.com",
        "login.microsoftonline.us",
        "login.partner.microsoftonline.cn",
    )

private val GRAPH_HOSTS =
    setOf(
        "graph.microsoft.com",
        "graph.microsoft.us",
        "dod-graph.microsoft.us",
        "microsoftgraph.chinacloudapi.cn",
    )

/**
 * Deployment-level HTTP settings for the Graph Mail plugin.
 *
 * These used to be `@PluginProperty` fields on [GraphMailPlugin], editable per plugin configuration
 * from the admin UI. That was a credential-exfiltration path: the client secret is POSTed to
 * `tokenBaseUrl` as a form field, so anyone who could edit a plugin configuration could point that
 * at a host they control and harvest it. `graphBaseUrl` was an SSRF primitive on top of that, and
 * the upload-URL host check derived its expected host *from* `graphBaseUrl` — so the check was only
 * ever as strong as the value an administrator typed in.
 *
 * Moving them here puts them under deployment control (application.yml / environment variables,
 * changed by whoever operates the platform) and lets them be validated once, at startup, against a
 * fixed allowlist. They are also genuinely deployment concerns rather than per-configuration ones:
 * two plugin configurations in the same GZAC instance have no reason to talk to different clouds.
 */
@ConfigurationProperties("graph-mail.http")
data class GraphMailHttpProperties(
    val tokenBaseUrl: String = "https://login.microsoftonline.com",
    val graphBaseUrl: String = "https://graph.microsoft.com",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 30,
    /**
     * Caps how many sends may hold attachment bytes in memory at once. Peak attachment heap is
     * roughly this value × [MAX_TOTAL_ATTACHMENT_BYTES] × a small copying factor — deliberately
     * decoupled from the job-executor pool size, which would otherwise set it.
     */
    val attachmentConcurrency: Int = 8,
    /** How long a send waits for an attachment slot before giving up and letting the engine retry. */
    val attachmentAcquireTimeoutSeconds: Long = 30,
    /**
     * Escape hatch for tests and local sandboxes pointing at WireMock or a proxy. Disables the
     * Microsoft endpoint allowlist below — never enable it in a production deployment.
     */
    val allowNonMicrosoftEndpoints: Boolean = false,
) {
    init {
        require(connectTimeoutSeconds in 1..120) {
            "graph-mail.http.connect-timeout-seconds must be between 1 and 120 (got $connectTimeoutSeconds). " +
                "A value of 0 means 'wait forever' in some request factories, which is the opposite " +
                "of what a timeout is for."
        }
        require(readTimeoutSeconds in 1..300) {
            "graph-mail.http.read-timeout-seconds must be between 1 and 300 (got $readTimeoutSeconds)"
        }
        require(attachmentConcurrency in 1..256) {
            "graph-mail.http.attachment-concurrency must be between 1 and 256 (got $attachmentConcurrency)"
        }
        require(attachmentAcquireTimeoutSeconds in 1..600) {
            "graph-mail.http.attachment-acquire-timeout-seconds must be between 1 and 600 " +
                "(got $attachmentAcquireTimeoutSeconds)"
        }
        if (!allowNonMicrosoftEndpoints) {
            requireMicrosoftEndpoint(tokenBaseUrl, TOKEN_HOSTS, "graph-mail.http.token-base-url")
            requireMicrosoftEndpoint(graphBaseUrl, GRAPH_HOSTS, "graph-mail.http.graph-base-url")
        }
    }

    /**
     * True when [graphBaseUrl] points at a real Microsoft Graph host. The upload-URL check uses this
     * to decide how strict it can be: against production Graph it demands a Microsoft host, while a
     * WireMock sandbox is allowed to hand back its own host. Safe to derive from configuration only
     * because [init] has already validated that configuration.
     */
    fun isProductionGraphEndpoint(): Boolean = runCatching { URI.create(graphBaseUrl).host }.getOrNull() in GRAPH_HOSTS
}

private fun requireMicrosoftEndpoint(
    url: String,
    allowed: Set<String>,
    property: String,
) {
    val uri = runCatching { URI.create(url) }.getOrNull()
    require(uri?.scheme == "https" && uri.host in allowed) {
        "$property must be an https URL on one of $allowed (got '$url'). " +
            "Set graph-mail.http.allow-non-microsoft-endpoints=true only in tests or a local sandbox."
    }
}
