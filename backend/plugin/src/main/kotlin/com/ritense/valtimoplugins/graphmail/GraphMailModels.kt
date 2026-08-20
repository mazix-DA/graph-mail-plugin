package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

const val GRAPH_BODY_CONTENT_TYPE_HTML = "HTML"
const val GRAPH_BODY_CONTENT_TYPE_TEXT = "Text"

// Attachments at or below this threshold are embedded inline (base64) in the sendMail JSON body.
// Larger files use the upload-session flow (create draft → chunked PUT → send).
const val INLINE_ATTACHMENT_THRESHOLD_BYTES = 2L * 1024L * 1024L

// Hard limits enforced before any API call — raised to 25 MB now that upload sessions are supported.
const val MAX_SINGLE_ATTACHMENT_BYTES = 25L * 1024L * 1024L
const val MAX_TOTAL_ATTACHMENT_BYTES = 25L * 1024L * 1024L
const val MAX_ATTACHMENTS = 5

// Upload-session chunk size — must be a non-zero multiple of 320 KiB (327,680 bytes).
// 327,680 × 10 = 3,276,800 bytes (3,200 KiB). Stays under 4 MB and is exactly divisible.
const val UPLOAD_CHUNK_BYTES = 327_680L * 10L

// Internal holder — keeps raw bytes until sendMail decides inline vs. upload-session path.
// Not a data class: ByteArray equality is reference-based, which would cause surprising behaviour.
class ResolvedAttachment(
    val name: String,
    val contentType: String,
    val rawBytes: ByteArray,
) {
    val sizeBytes: Long get() = rawBytes.size.toLong()
}

// Azure Entra app registration credentials for OAuth2 Client Credentials — grouped so
// GraphMailClient callers pass one value instead of three positional strings repeated
// across the interface, the implementation, and every internal retry/draft-flow method.
data class GraphCredentials(
    val tenantId: String,
    val clientId: String,
    val clientSecret: String,
) {
    // Kotlin's generated toString() would otherwise print clientSecret in plaintext — override
    // it so an accidental debug log statement, or a failed test assertion printing this object,
    // can never leak the secret.
    override fun toString(): String = "GraphCredentials(tenantId=$tenantId, clientId=$clientId, clientSecret=***)"
}

// Everything needed to send one email via Graph, grouped for the same reason as
// [GraphCredentials]. Optional recipient lists and saveToSentItems default so callers only
// need to name the fields that matter for their call site.
data class OutboundMail(
    val senderMailbox: String,
    val toRecipients: List<GraphRecipient>,
    val ccRecipients: List<GraphRecipient> = emptyList(),
    val bccRecipients: List<GraphRecipient> = emptyList(),
    val replyToRecipients: List<GraphRecipient> = emptyList(),
    val subject: String,
    val bodyHtml: String,
    val attachments: List<ResolvedAttachment> = emptyList(),
    val saveToSentItems: Boolean = true,
)

internal data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Int,
)

data class SendMailRequest(
    @JsonProperty("message") val message: GraphMessage,
    @JsonProperty("saveToSentItems") val saveToSentItems: Boolean,
)

data class GraphMessage(
    @JsonProperty("subject") val subject: String,
    @JsonProperty("body") val body: GraphBody,
    @JsonProperty("toRecipients") val toRecipients: List<GraphRecipient>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("ccRecipients") val ccRecipients: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("bccRecipients") val bccRecipients: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("replyTo") val replyTo: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("attachments") val attachments: List<GraphAttachment> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("from") val from: GraphRecipient? = null,
)

data class GraphBody(
    @JsonProperty("contentType") val contentType: String,
    @JsonProperty("content") val content: String,
)

data class GraphRecipient(
    @JsonProperty("emailAddress") val emailAddress: GraphEmailAddress,
)

data class GraphEmailAddress(
    @JsonProperty("address") val address: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("name") val name: String? = null,
)

data class GraphAttachment(
    @JsonProperty("@odata.type") val odataType: String = "#microsoft.graph.fileAttachment",
    @JsonProperty("name") val name: String,
    @JsonProperty("contentType") val contentType: String,
    @JsonProperty("contentBytes") val contentBytes: String,
)

// ── Upload-session models ─────────────────────────────────────────────────────

internal data class DraftMessageResponse(
    @JsonProperty("id") val id: String,
)

data class CreateUploadSessionRequest(
    @JsonProperty("AttachmentItem") val attachmentItem: UploadAttachmentItem,
)

data class UploadAttachmentItem(
    @JsonProperty("attachmentType") val attachmentType: String = "file",
    @JsonProperty("name") val name: String,
    @JsonProperty("size") val size: Long,
    @JsonProperty("contentType") val contentType: String,
)

internal data class UploadSessionResponse(
    @JsonProperty("uploadUrl") val uploadUrl: String,
    @JsonProperty("expirationDateTime") val expirationDateTime: String? = null,
)
