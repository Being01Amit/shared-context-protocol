package com.scp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    private fun redact(s: String) = Redaction.redact(s)

    @Test
    fun `aws access key is redacted`() {
        val out = redact("creds: AKIAIOSFODNN7EXAMPLE used in test")
        assertFalse("AKIAIOSFODNN7EXAMPLE" in out)
        assertTrue("[REDACTED:aws-access-key]" in out)
    }

    @Test
    fun `github tokens are redacted`() {
        val out = redact("token ghp_16C7e42F292c6912E7710c838347Ae178B4a and github_pat_11ABCDEFG0_abcdefghijklmnopqrstuv")
        assertFalse("ghp_16C7e42F292c6912E7710c838347Ae178B4a" in out)
        assertTrue("[REDACTED:github-token]" in out)
        assertTrue("[REDACTED:github-pat]" in out)
    }

    @Test
    fun `anthropic key redacts under its own name not generic`() {
        val out = redact("use sk-ant-api03-abcdefghijklmnopqrstuvwx here")
        assertTrue("[REDACTED:anthropic-key]" in out)
        assertFalse("sk-ant" in out)
    }

    @Test
    fun `generic sk key is redacted`() {
        val out = redact("openai sk-abcdefghijklmnopqrstuvwxyz123456")
        assertTrue("[REDACTED:generic-sk-key]" in out)
    }

    @Test
    fun `jwt is redacted`() {
        val jwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
                ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = redact("Authorization used $jwt")
        assertTrue("[REDACTED:jwt]" in out)
        assertFalse("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" in out)
    }

    @Test
    fun `bearer token is redacted`() {
        val out = redact("header: Bearer abcdefghijklmnopqrstuvwxyz0123456789")
        assertTrue("[REDACTED:bearer-token]" in out)
    }

    @Test
    fun `private key block is redacted across lines`() {
        val out =
            redact(
                """
                before
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA7bq7
                x9lines
                -----END RSA PRIVATE KEY-----
                after
                """.trimIndent(),
            )
        assertFalse("MIIEowIBAAKCAQEA7bq7" in out)
        assertTrue("[REDACTED:private-key-block]" in out)
        assertTrue("before" in out && "after" in out)
    }

    @Test
    fun `env style secret keeps the key name but hides the value`() {
        val out = redact("DB_PASSWORD=hunter2\nAPI_KEY=abc123\nNORMAL_VAR=fine")
        assertFalse("hunter2" in out)
        assertFalse("abc123" in out)
        assertTrue("DB_PASSWORD=[REDACTED:env-secret]" in out)
        assertTrue("API_KEY=[REDACTED:env-secret]" in out)
        assertTrue("NORMAL_VAR=fine" in out, "non-secret env vars pass through")
    }

    @Test
    fun `look-alikes survive - short sk prefix and prose`() {
        val text = "we used sk-learn and a token bucket algorithm; TASK=done"
        assertEquals(text, redact(text))
    }

    @Test
    fun `custom config patterns extend the built-ins`() {
        val patterns = Redaction.compile(listOf("""\bACME-[0-9]{6}\b"""))
        val out = Redaction.redact("id ACME-123456 plus AKIAIOSFODNN7EXAMPLE", patterns)
        assertTrue("[REDACTED:custom-0]" in out)
        assertTrue("[REDACTED:aws-access-key]" in out, "built-ins still active")
    }
}
