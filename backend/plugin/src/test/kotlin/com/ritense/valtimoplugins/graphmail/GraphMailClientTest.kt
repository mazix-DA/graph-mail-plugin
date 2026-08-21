/*
 * Copyright 2015-2025 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

class GraphMailClientTest {
    private lateinit var wireMock: WireMockServer
    private lateinit var client: GraphMailClientImpl

    private val token = "test-token-123"
    private val mailbox = "noreply@test.nl"
    private val tenantId = "tenant-A"
    private val clientId = "client-A"
    private val clientSecret = "secret-A"
    private val tokenPath = ".*/oauth2/v2.0/token"
    private val mailPath = ".*/sendMail"

    private fun tokenJson(
        t: String = token,
        exp: Int = 3600,
    ) = """{"access_token":"$t","token_type":"Bearer","expires_in":$exp}"""

    private fun stubToken(
        t: String = token,
        exp: Int = 3600,
    ) {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(okJson(tokenJson(t, exp))))
    }

    private fun recipients(vararg addresses: String) = addresses.map { GraphRecipient(GraphEmailAddress(address = it)) }

    private fun credentials(
        tenant: String = tenantId,
        client: String = clientId,
        secret: String = clientSecret,
    ) = GraphCredentials(tenant, client, secret)

    private fun sendBasic(saveToSentItems: Boolean = true) =
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("jan@test.nl"),
                subject = "Test",
                bodyHtml = "<p>Test</p>",
                saveToSentItems = saveToSentItems,
            ),
        )

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        val mapper = ObjectMapper().registerKotlinModule()

        client =
            GraphMailClientImpl(
                // Built the same way GraphMailAutoConfiguration.graphMailRestClient builds it. The
                // transport-failure tests assert on which exception a connection reset produces, and
                // that is a property of the request factory — testing a plain RestTemplate would prove
                // nothing about the client that actually ships.
                restClient =
                    RestClient
                        .builder()
                        .requestFactory(
                            JdkClientHttpRequestFactory(
                                HttpClient
                                    .newBuilder()
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .followRedirects(HttpClient.Redirect.NEVER)
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .build(),
                            ).apply { setReadTimeout(Duration.ofSeconds(30)) },
                        ).messageConverters { converters ->
                            converters.removeIf { it is MappingJackson2HttpMessageConverter }
                            converters.add(0, MappingJackson2HttpMessageConverter(mapper))
                        }.build(),
                tokenBaseUrl = wireMock.baseUrl(),
                graphBaseUrl = wireMock.baseUrl(),
                // WireMock is not a Microsoft host, so the strict upload-host check is relaxed here —
                // exactly the case GraphMailHttpProperties.isProductionGraphEndpoint() reports false for.
                // The relaxed check still pins the upload URL to the very host we are talking to.
                requireMicrosoftUploadHost = false,
            )
    }

    @AfterEach
    fun tearDown() = wireMock.stop()

    // ── Credentials ────────────────────────────────────────────────────────

    @Test fun `GraphCredentials toString never includes the clientSecret`() {
        val text = GraphCredentials("t", "c", "super-secret-value").toString()
        assertFalse(text.contains("super-secret-value"))
        assertTrue(text.contains("***"))
    }

    // ── Token ──────────────────────────────────────────────────────────────

    @Test fun `the client-credentials scope follows the configured Graph endpoint`() {
        // A hard-coded commercial scope would make every send fail at the token step for the
        // sovereign clouds the endpoint allowlist accepts.
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .withRequestBody(
                    containing(
                        "scope=" +
                            java.net.URLEncoder.encode(
                                "${wireMock.baseUrl()}/.default",
                                "UTF-8",
                            ),
                    ),
                ).willReturn(okJson(tokenJson())),
        )

        client.getAccessToken(GraphCredentials("t", "c", "s"))

        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `fetches token`() {
        stubToken()
        assertEquals(token, client.getAccessToken(GraphCredentials("t", "c", "s")))
    }

    @Test fun `sends correct credentials`() {
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .withRequestBody(containing("client_id=myid"))
                .withRequestBody(containing("client_secret=mysecret"))
                .willReturn(okJson(tokenJson())),
        )
        client.getAccessToken(GraphCredentials("t", "myid", "mysecret"))
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `caches token on second call`() {
        stubToken()
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `fetches new token after cache expiry`() {
        stubToken(exp = 59) // < TOKEN_EXPIRY_BUFFER_SECONDS so each call refreshes
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `different tenants use separate cache entries`() {
        stubToken()
        client.getAccessToken(GraphCredentials("tenant1", "c", "s"))
        client.getAccessToken(GraphCredentials("tenant2", "c", "s"))
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `different secret for the same tenant and client does not reuse the cached token`() {
        // Regression test: the cache key used to be "tenantId:clientId" only, so a plugin
        // configuration with a wrong or different secret could silently reuse a token that a
        // different (correct) secret already fetched for the same tenant/client, without Azure
        // Entra ever validating that secret. The cache key must include the secret.
        stubToken()
        client.getAccessToken(GraphCredentials("t", "c", "correct-secret"))
        client.getAccessToken(GraphCredentials("t", "c", "different-secret"))
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `same tenant, client and secret still hits the cache`() {
        stubToken()
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        client.getAccessToken(GraphCredentials("t", "c", "s"))
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `throws on 400 token request`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(aResponse().withStatus(400)))
        assertThrows(GraphMailException::class.java) { client.getAccessToken(GraphCredentials("t", "c", "s")) }
    }

    @Test fun `retries token on 503 then succeeds`() {
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-5xx")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-5xx")
                .whenScenarioStateIs("ok")
                .willReturn(okJson(tokenJson())),
        )
        assertEquals(token, client.getAccessToken(GraphCredentials("t", "c", "s")))
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `gives up on token after MAX retries on 503`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(aResponse().withStatus(503)))
        assertThrows(GraphMailException::class.java) { client.getAccessToken(GraphCredentials("t", "c", "s")) }
    }

    @Test fun `invalidateCache key-scoped only clears that key`() {
        stubToken()
        client.getAccessToken(GraphCredentials("tenant1", "client1", "s"))
        client.getAccessToken(GraphCredentials("tenant2", "client2", "s"))
        client.invalidateCache("tenant1", "client1")
        client.getAccessToken(GraphCredentials("tenant1", "client1", "s"))
        client.getAccessToken(GraphCredentials("tenant2", "client2", "s"))
        // tenant1 fetched twice (initial + post-invalidate); tenant2 fetched once total
        wireMock.verify(3, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `invalidateCache full flush clears everything`() {
        stubToken()
        client.getAccessToken(GraphCredentials("tenant1", "c", "s"))
        client.getAccessToken(GraphCredentials("tenant2", "c", "s"))
        client.invalidateCache()
        client.getAccessToken(GraphCredentials("tenant1", "c", "s"))
        client.getAccessToken(GraphCredentials("tenant2", "c", "s"))
        wireMock.verify(4, postRequestedFor(urlPathMatching(tokenPath)))
    }

    // ── SendMail success ────────────────────────────────────────────────────

    @Test fun `sends mail successfully — fetches token first`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .withHeader("Authorization", equalTo("Bearer $token"))
                .willReturn(aResponse().withStatus(202)),
        )
        sendBasic()
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `saveToSentItems false in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic(saveToSentItems = false)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""saveToSentItems":false"""))
    }

    @Test fun `saveToSentItems true in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic(saveToSentItems = true)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""saveToSentItems":true"""))
    }

    // ── JSON structure ───────────────────────────────────────────────────────

    @Test fun `3 recipients exact in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("a@t.nl", "b@t.nl", "c@t.nl"),
                subject = "Sub",
                bodyHtml = "<p>B</p>",
            ),
        )
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""address":"a@t.nl""""))
        assertTrue(body.contains(""""address":"b@t.nl""""))
        assertTrue(body.contains(""""address":"c@t.nl""""))
    }

    @Test fun `CC and BCC present in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("to@t.nl"),
                ccRecipients = recipients("cc@t.nl"),
                bccRecipients = recipients("bcc@t.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
            ),
        )
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""ccRecipients"""))
        assertTrue(body.contains(""""bccRecipients"""))
    }

    @Test fun `empty ccRecipients omitted from JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic()
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertFalse(body.contains(""""ccRecipients"""))
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test fun `401 once triggers refresh and retry, succeeds`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("401-then-ok")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("401-then-ok")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )
        sendBasic()
        // First send 401 → cache invalidated → fresh token fetched → retry sends 202
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `401 twice throws GraphMailTokenExpiredException`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(401)))
        assertThrows(GraphMailTokenExpiredException::class.java) { sendBasic() }
    }

    @Test fun `403 throws with status`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(403)))
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        assertTrue(ex.message!!.contains("403"))
    }

    @Test fun `429 rate limit retries`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("rl")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("rl")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )
        sendBasic()
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `429 with no wait still exhausts the attempt limit`() {
        // Retry-After: 0 costs no sleep, so the wait budget never bites and the attempt limit is
        // what stops it. Keeps the two limits distinguishable.
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        assertTrue(ex.message!!.contains("Rate limited") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `three consecutive 429s on sendMail then success`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("3x-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("r1"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("3x-429")
                .whenScenarioStateIs("r1")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("r2"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("3x-429")
                .whenScenarioStateIs("r2")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("3x-429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )
        sendBasic()
        wireMock.verify(4, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `retry on 503 succeeds on 2nd attempt`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("r")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("r")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )
        sendBasic()
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `a persistent 503 hands back to the job executor instead of sleeping on`() {
        // Used to burn all five attempts, sleeping 500 + 1000 + 2000 + 4000ms on an Operaton
        // job-executor thread. That pool is shared with every other job in the engine, so a Graph
        // outage stalled unrelated work. Now the call stops once its wait budget is spent and
        // returns something retryable; the engine reschedules without holding a thread.
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(503)))

        val started = System.currentTimeMillis()
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        val elapsed = System.currentTimeMillis() - started

        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
        assertTrue(
            elapsed < 10_000,
            "the call spent ${elapsed}ms before giving up — it should hand back to the job " +
                "executor rather than sleep through the whole outage",
        )
    }

    @Test fun `a persistent 429 hands back rather than sleeping through the throttling`() {
        // Graph throttles mail per mailbox and does it hard. Honouring a long Retry-After in-call
        // is what turned one throttled mailbox into an engine-wide stall.
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "15")),
        )

        val started = System.currentTimeMillis()
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        val elapsed = System.currentTimeMillis() - started

        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
        assertTrue(
            elapsed < 10_000,
            "the call waited ${elapsed}ms on a 15s Retry-After — it should hand back instead",
        )
    }

    @Test fun `a short blip is still absorbed in-call rather than bounced to the engine`() {
        // The budget must not make every hiccup a rescheduled job: a single fast retry is cheaper
        // than a round trip through the job executor.
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("brief-503")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("brief-503")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )

        sendBasic()

        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `empty recipients throws IllegalArgumentException`() {
        stubToken()
        assertThrows(IllegalArgumentException::class.java) {
            client.sendMail(
                credentials(),
                OutboundMail(
                    senderMailbox = mailbox,
                    toRecipients = emptyList(),
                    subject = "T",
                    bodyHtml = "<p>B</p>",
                ),
            )
        }
    }

    @Test fun `mailbox with special chars is URL-encoded in path`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        // + sign in mailbox local part should not break the URI
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = "user+tag@test.nl",
                toRecipients = recipients("jan@t.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
            ),
        )
        val req = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0]
        assertTrue(
            req.url.contains("user%2Btag%40test.nl") || req.url.contains("user+tag@test.nl"),
            "Expected encoded or original mailbox in path, got: ${req.url}",
        )
    }

    // ── Attachment routing ────────────────────────────────────────────────────

    private fun resolvedAttachment(
        name: String,
        sizeBytes: Int,
    ): ResolvedAttachment =
        ResolvedAttachment(
            name = name,
            contentType = "application/octet-stream",
            rawBytes = ByteArray(sizeBytes),
        )

    private val draftPath = ".*/messages$"
    private val uploadSessionPath = ".*/attachments/createUploadSession$"
    private val sendDraftPath = ".*/messages/.*/send$"

    private fun stubDraftCreate(draftId: String = "draft-1") {
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .willReturn(okJson("""{"id":"$draftId"}""")),
        )
    }

    private fun stubUploadSession(uploadUrl: String) {
        wireMock.stubFor(
            post(urlPathMatching(uploadSessionPath))
                .willReturn(okJson("""{"uploadUrl":"$uploadUrl"}""")),
        )
    }

    private fun stubSendDraft() {
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .willReturn(aResponse().withStatus(202)),
        )
    }

    @Test fun `small attachment stays on inline sendMail path`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        val attachment = resolvedAttachment("small.pdf", INLINE_ATTACHMENT_THRESHOLD_BYTES.toInt())
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("jan@test.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
                attachments = listOf(attachment),
            ),
        )
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
        wireMock.verify(0, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `large attachment uses draft upload path`() {
        stubToken()
        val uploadUrl = "${wireMock.baseUrl()}/upload/session-abc"
        stubDraftCreate("draft-42")
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        val attachment = resolvedAttachment("large.bin", (INLINE_ATTACHMENT_THRESHOLD_BYTES + 1).toInt())
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("jan@test.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
                attachments = listOf(attachment),
            ),
        )

        wireMock.verify(0, postRequestedFor(urlPathMatching(mailPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(draftPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(uploadSessionPath)))
        wireMock.verify(1, putRequestedFor(anyUrl()))
        wireMock.verify(1, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `chunk upload sets Content-Range header correctly`() {
        stubToken()
        // attachment slightly larger than one chunk — expect 2 PUT calls
        val chunkSize = UPLOAD_CHUNK_BYTES.toInt()
        val totalSize = chunkSize + 1
        val uploadUrl = "${wireMock.baseUrl()}/upload/range-test"
        stubDraftCreate()
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("jan@test.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
                attachments = listOf(resolvedAttachment("f.bin", totalSize)),
            ),
        )

        val puts = wireMock.findAll(putRequestedFor(anyUrl()))
        assertEquals(2, puts.size)
        val firstRange = puts[0].getHeader("Content-Range")
        // first chunk: bytes 0-(chunkSize-1)/totalSize
        assertEquals("bytes 0-${chunkSize - 1}/$totalSize", firstRange)
        val secondRange = puts[1].getHeader("Content-Range")
        assertEquals("bytes $chunkSize-${totalSize - 1}/$totalSize", secondRange)
    }

    // ── Draft flow: createDraft error handling ────────────────────────────────

    private fun sendLarge(
        attachment: ResolvedAttachment =
            resolvedAttachment(
                "f.bin",
                (
                    INLINE_ATTACHMENT_THRESHOLD_BYTES +
                        1
                ).toInt(),
            ),
    ) = client.sendMail(
        credentials(),
        OutboundMail(
            senderMailbox = mailbox,
            toRecipients = recipients("jan@test.nl"),
            subject = "T",
            bodyHtml = "<p>B</p>",
            attachments = listOf(attachment),
        ),
    )

    @Test fun `401 on createDraft triggers cache invalidate and retry`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("draft-401")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("draft-401")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""{"id":"draft-1"}""")),
        )
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        // initial token + post-invalidate fresh token
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `401 twice on createDraft throws GraphMailTokenExpiredException`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(draftPath)).willReturn(aResponse().withStatus(401)))

        assertThrows(GraphMailTokenExpiredException::class.java) { sendLarge() }
    }

    @Test fun `429 on createDraft retries and succeeds`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("draft-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("draft-429")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""{"id":"draft-1"}""")),
        )
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `429 exhausts all attempts on createDraft`() {
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("Rate limited during draft creation") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `429 on createDraft with RFC 1123 Retry-After parses and retries`() {
        stubToken()
        // Past date → parseRetryAfter returns 0 → no sleep → fast test
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("rfc1123")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "Thu, 01 Jan 1970 00:00:00 GMT"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(draftPath))
                .inScenario("rfc1123")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""{"id":"draft-1"}""")),
        )
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `network error on createDraft retries and succeeds`() {
        // Regression test: createDraft previously did not catch ResourceAccessException at all,
        // so a connection reset here would abort the whole send instead of retrying like the
        // inline sendMail path does.
        stubToken()
        val firstAttempt =
            post(urlPathMatching(draftPath))
                .inScenario("draft-network-error")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("ok")
        wireMock.stubFor(firstAttempt)
        val secondAttempt =
            post(urlPathMatching(draftPath))
                .inScenario("draft-network-error")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""{"id":"draft-1"}"""))
        wireMock.stubFor(secondAttempt)
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    // ── Draft flow: upload session error handling ─────────────────────────────

    @Test fun `401 twice on upload session creation throws GraphMailTokenExpiredException`() {
        // Regression test: this endpoint previously only retried a 401 once but, on a second
        // 401, threw a plain GraphMailException instead of GraphMailTokenExpiredException like
        // every other Graph API call site in this class.
        stubToken()
        stubDraftCreate()
        wireMock.stubFor(post(urlPathMatching(uploadSessionPath)).willReturn(aResponse().withStatus(401)))

        assertThrows(GraphMailTokenExpiredException::class.java) { sendLarge() }
    }

    @Test fun `429 on upload session creation retries and succeeds`() {
        // Regression test: this endpoint previously did not retry 429/5xx/network errors at all.
        stubToken()
        stubDraftCreate()
        val firstAttempt =
            post(urlPathMatching(uploadSessionPath))
                .inScenario("upload-session-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok")
        wireMock.stubFor(firstAttempt)
        val secondAttempt =
            post(urlPathMatching(uploadSessionPath))
                .inScenario("upload-session-429")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""{"uploadUrl":"${wireMock.baseUrl()}/upload/s1"}"""))
        wireMock.stubFor(secondAttempt)
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(uploadSessionPath)))
    }

    // ── Draft flow: chunk upload error handling ───────────────────────────────

    @Test fun `chunk upload retries on 503 and succeeds`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-retry"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-retry"))
                .inScenario("chunk-5xx")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-retry"))
                .inScenario("chunk-5xx")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)),
        )
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, putRequestedFor(urlPathMatching(".*/upload/chunk-retry")))
    }

    @Test fun `429 on chunk upload is retried and eventually reported as retryable`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-429"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-429"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("429"))
        // Throttling on an upload session is transient, so it must reach the job executor as
        // retryable rather than being reported as a permanent failure.
        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
        wireMock.verify(3, putRequestedFor(urlPathMatching(".*/upload/chunk-429")))
    }

    @Test fun `429 on chunk upload recovers when the next attempt succeeds`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-429-recover"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-429-recover"))
                .inScenario("chunk-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-429-recover"))
                .inScenario("chunk-429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)),
        )
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, putRequestedFor(urlPathMatching(".*/upload/chunk-429-recover")))
    }

    @Test fun `4xx other than 429 on chunk upload fails permanently without retry`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-400"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/chunk-400"))
                .willReturn(aResponse().withStatus(400)),
        )

        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }

        assertTrue(ex is GraphMailPermanentException, "expected permanent, got ${ex::class.simpleName}")
        wireMock.verify(1, putRequestedFor(urlPathMatching(".*/upload/chunk-400")))
    }

    @Test fun `a rewinding nextExpectedRanges is followed instead of skipping ahead`() {
        // Graph reporting an earlier range means it did NOT commit what we just sent. Advancing to
        // end + 1 anyway would leave a hole in the attachment, so the reported offset wins even
        // when it points backwards.
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/rewind"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/rewind"))
                .inScenario("rewind")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""{"nextExpectedRanges":["0-"]}"""))
                .willSetStateTo("committed"),
        )
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/rewind"))
                .inScenario("rewind")
                .whenScenarioStateIs("committed")
                .willReturn(aResponse().withStatus(200)),
        )
        stubSendDraft()

        sendLarge()

        // Both PUTs must start at byte 0 — the second is a genuine re-send of the declined range,
        // not a jump past it.
        wireMock.verify(
            2,
            putRequestedFor(urlPathMatching(".*/upload/rewind"))
                .withHeader("Content-Range", containing("bytes 0-")),
        )
    }

    @Test fun `an upload that never advances fails as retryable instead of looping forever`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/stalled"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/stalled"))
                .willReturn(okJson("""{"nextExpectedRanges":["0-"]}""")),
        )

        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }

        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
        assertTrue(ex.message!!.contains("no progress"))
    }

    @Test fun `an out-of-range nextExpectedRanges offset aborts the upload`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/bogus"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(
            put(urlPathMatching(".*/upload/bogus"))
                .willReturn(okJson("""{"nextExpectedRanges":["999999999-"]}""")),
        )

        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }

        assertTrue(ex.message!!.contains("out-of-range"))
    }

    @Test fun `upload URL on a foreign host is rejected before any content is sent`() {
        stubToken()
        stubDraftCreate()
        // A hostile or compromised upload URL must never receive attachment bytes — the PUT to an
        // upload session carries no bearer token precisely because the URL itself is the capability.
        stubUploadSession("https://attacker.example/steal")

        assertThrows(GraphMailPermanentException::class.java) { sendLarge() }

        wireMock.verify(0, putRequestedFor(urlPathMatching(".*/steal")))
    }

    // ── Non-idempotent sends are never retried on a transport failure ────────

    @Test fun `transport failure on inline send is not retried`() {
        // A connection reset says nothing about whether Graph accepted the message. Retrying is a
        // coin flip that costs the recipient a duplicate when it lands wrong, so the send must fail
        // once, loudly, with an outcome the operator knows to verify.
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }

        assertTrue(ex is GraphMailUnknownOutcomeException, "expected unknown outcome, got ${ex::class.simpleName}")
        assertTrue(ex.message!!.contains("UNKNOWN"))
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `transport failure on draft send is not retried`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        assertThrows(GraphMailUnknownOutcomeException::class.java) { sendLarge() }

        wireMock.verify(1, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `a failed draft send does not delete the message`() {
        // The draft id moves to Sent Items once /send succeeds. If the response times out after the
        // message actually went out, deleting that id destroys the record of a delivered email.
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        assertThrows(GraphMailException::class.java) { sendLarge() }

        wireMock.verify(0, deleteRequestedFor(urlPathMatching(".*/messages/draft-1")))
    }

    @Test fun `a failed attachment upload does delete the orphaned draft`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/fails")
        wireMock.stubFor(put(urlPathMatching(".*/upload/fails")).willReturn(aResponse().withStatus(400)))
        wireMock.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(204)))

        assertThrows(GraphMailException::class.java) { sendLarge() }

        wireMock.verify(1, deleteRequestedFor(urlPathMatching(".*/messages/draft-1")))
    }

    // ── 401 refresh must produce a genuinely new token ───────────────────────

    @Test fun `401 refresh uses the newly fetched token, not the cached one`() {
        // The 401 handler used to invalidate the cache entry and simply read it again. Under
        // concurrency that could hand back the very token that was just refused; here the second
        // token request returns a different value and the retry must carry it.
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-rotation")
                .whenScenarioStateIs("Started")
                .willReturn(okJson(tokenJson("stale-token", 3600)))
                .willSetStateTo("rotated"),
        )
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-rotation")
                .whenScenarioStateIs("rotated")
                .willReturn(okJson(tokenJson("fresh-token", 3600))),
        )

        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("send-401")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .inScenario("send-401")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )

        sendBasic()

        wireMock.verify(
            postRequestedFor(urlPathMatching(mailPath))
                .withHeader("Authorization", equalTo("Bearer fresh-token")),
        )
    }

    @Test fun `a transport failure message does not leak the sender mailbox`() {
        // RestClientException embeds the request URI, and the mailbox sits in that path
        // (/v1.0/users/{mailbox}/sendMail) — so a bare ex.message puts a real address into the
        // logs and, via the test-send endpoint, in front of an administrator.
        stubToken()
        wireMock.stubFor(
            post(urlPathMatching(mailPath))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }

        assertFalse(
            ex.message!!.contains(mailbox),
            "sender mailbox leaked into the exception message: ${ex.message}",
        )
    }

    @Test fun `a connection that never reaches Graph is retryable, not unknown`() {
        // DNS/connect/TLS failures happen before anything is submitted, so nothing can have been
        // delivered — calling that UNKNOWN would send an operator hunting a message that was never
        // sent, and would block a retry that is provably safe.
        stubToken()
        val unreachable =
            GraphMailClientImpl(
                restClient =
                    RestClient
                        .builder()
                        .requestFactory(
                            JdkClientHttpRequestFactory(
                                HttpClient
                                    .newBuilder()
                                    .connectTimeout(Duration.ofSeconds(2))
                                    .version(HttpClient.Version.HTTP_1_1)
                                    .build(),
                            ).apply { setReadTimeout(Duration.ofSeconds(2)) },
                        ).build(),
                tokenBaseUrl = wireMock.baseUrl(),
                // A host that cannot resolve — the failure lands in the connect phase.
                graphBaseUrl = "http://graph-mail-plugin.invalid",
                requireMicrosoftUploadHost = false,
            )

        val ex =
            assertThrows(GraphMailException::class.java) {
                unreachable.sendMail(
                    credentials(),
                    OutboundMail(
                        senderMailbox = mailbox,
                        toRecipients = recipients("jan@test.nl"),
                        subject = "Test",
                        bodyHtml = "<p>Test</p>",
                    ),
                )
            }

        assertTrue(
            ex is GraphMailRetryableException,
            "a never-submitted request must be retryable, got ${ex::class.simpleName}: ${ex.message}",
        )
    }

    @Test fun `a 429 from the token endpoint is retried instead of blamed on the credentials`() {
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .inScenario("token-429")
                .whenScenarioStateIs("ok")
                .willReturn(okJson(tokenJson())),
        )

        assertEquals(token, client.getAccessToken(GraphCredentials("t", "c", "s")))

        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `a persistent 429 from the token endpoint is reported as retryable`() {
        wireMock.stubFor(
            post(urlPathMatching(tokenPath))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )

        val ex =
            assertThrows(GraphMailException::class.java) {
                client.getAccessToken(GraphCredentials("t", "c", "s"))
            }

        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
    }

    @Test fun `a rejected upload URL is permanent, not an input error`() {
        // IllegalArgumentException here would be classified as PERMANENT_INPUT and tell the
        // administrator to fix the process data, when the cause is a Graph response.
        stubToken()
        stubDraftCreate()
        stubUploadSession("https://attacker.example/steal")

        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }

        assertTrue(ex is GraphMailPermanentException, "expected permanent, got ${ex::class.simpleName}")
    }

    // ── Error classification ─────────────────────────────────────────────────

    @Test fun `403 is reported as permanent with an actionable remedy`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(403)))

        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }

        assertTrue(ex is GraphMailPermanentException, "expected permanent, got ${ex::class.simpleName}")
        assertTrue(ex.message!!.contains("Mail.Send"), "remedy should name the missing permission")
        // A permanent rejection must not burn the retry budget.
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `503 is reported as retryable`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(503)))

        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }

        assertTrue(ex is GraphMailRetryableException, "expected retryable, got ${ex::class.simpleName}")
    }

    // ── Draft flow: sendDraft error handling ──────────────────────────────────

    @Test fun `401 on sendDraft triggers cache invalidate and retry`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .inScenario("send-401")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .inScenario("send-401")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `429 on sendDraft retries and succeeds`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .inScenario("send-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"),
        )
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .inScenario("send-429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(202)),
        )

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `429 exhausts all attempts on sendDraft`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(
            post(urlPathMatching(sendDraftPath))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("Rate limited during draft send") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    // ── Draft flow: multiple attachments ──────────────────────────────────────

    @Test fun `two large attachments create two upload sessions and one draft send`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl1 = "${wireMock.baseUrl()}/upload/session-1"
        val uploadUrl2 = "${wireMock.baseUrl()}/upload/session-2"
        wireMock.stubFor(
            post(urlPathMatching(uploadSessionPath))
                .inScenario("two-sessions")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""{"uploadUrl":"$uploadUrl1"}"""))
                .willSetStateTo("second"),
        )
        wireMock.stubFor(
            post(urlPathMatching(uploadSessionPath))
                .inScenario("two-sessions")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""{"uploadUrl":"$uploadUrl2"}""")),
        )
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        val attachment = resolvedAttachment("f.bin", (INLINE_ATTACHMENT_THRESHOLD_BYTES + 1).toInt())
        client.sendMail(
            credentials(),
            OutboundMail(
                senderMailbox = mailbox,
                toRecipients = recipients("jan@test.nl"),
                subject = "T",
                bodyHtml = "<p>B</p>",
                attachments = listOf(attachment, attachment),
            ),
        )

        wireMock.verify(2, postRequestedFor(urlPathMatching(uploadSessionPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(sendDraftPath)))
    }
}
