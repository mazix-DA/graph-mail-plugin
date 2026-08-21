package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphMailHttpPropertiesTest {
    @Test fun `defaults are the Microsoft commercial cloud endpoints`() {
        val properties = GraphMailHttpProperties()
        assertEquals("https://login.microsoftonline.com", properties.tokenBaseUrl)
        assertEquals("https://graph.microsoft.com", properties.graphBaseUrl)
        assertTrue(properties.isProductionGraphEndpoint())
    }

    @Test fun `sovereign cloud endpoints are accepted`() {
        val properties = GraphMailHttpProperties(
            tokenBaseUrl = "https://login.microsoftonline.us",
            graphBaseUrl = "https://graph.microsoft.us",
        )
        assertTrue(properties.isProductionGraphEndpoint())
    }

    @Test fun `a foreign token endpoint is rejected`() {
        // This is the exfiltration path the allowlist exists for: the client secret is POSTed to
        // tokenBaseUrl as a form field, so a host outside the allowlist means handing it over.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(tokenBaseUrl = "https://attacker.example")
        }
        assertTrue(ex.message!!.contains("token-base-url"))
    }

    @Test fun `a foreign graph endpoint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(graphBaseUrl = "https://attacker.example")
        }
    }

    @Test fun `plain http is rejected even on an allowed host`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(tokenBaseUrl = "http://login.microsoftonline.com")
        }
    }

    @Test fun `a lookalike host is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(graphBaseUrl = "https://graph.microsoft.com.attacker.example")
        }
    }

    @Test fun `the test escape hatch allows a local endpoint and reports it as non-production`() {
        val properties = GraphMailHttpProperties(
            tokenBaseUrl = "http://localhost:8089",
            graphBaseUrl = "http://localhost:8089",
            allowNonMicrosoftEndpoints = true,
        )
        // The strict upload-host check must switch off exactly here, and nowhere else.
        assertFalse(properties.isProductionGraphEndpoint())
    }

    @Test fun `a zero timeout is rejected because it means wait forever`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(connectTimeoutSeconds = 0)
        }
        assertTrue(ex.message!!.contains("connect-timeout-seconds"))
    }

    @Test fun `an out of range attachment concurrency is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(attachmentConcurrency = 0)
        }
    }
}
