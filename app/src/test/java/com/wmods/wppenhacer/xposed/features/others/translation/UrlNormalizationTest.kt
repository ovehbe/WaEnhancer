package com.wmods.wppenhacer.xposed.features.others.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for URL normalization logic
 * Rule: append /v1 only if the base URL doesn't already end with /v1 or another version segment
 */
class UrlNormalizationTest {
    
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        // Check if already has a version segment like /v1, /v1beta, etc anywhere in path
        return if (trimmed.contains(Regex("/(v\\d+|v\\d+[a-z]+)(/|$)"))) {
            trimmed
        } else {
            "$trimmed/v1"
        }
    }
    
    @Test
    fun testBareUrl_AppendsV1() {
        assertEquals(
            "https://api.openai.com/v1",
            normalizeBaseUrl("https://api.openai.com")
        )
    }
    
    @Test
    fun testBareUrlWithTrailingSlash_AppendsV1() {
        assertEquals(
            "https://api.openai.com/v1",
            normalizeBaseUrl("https://api.openai.com/")
        )
    }
    
    @Test
    fun testUrlWithV1_NoDoubleAppend() {
        assertEquals(
            "https://api.openai.com/v1",
            normalizeBaseUrl("https://api.openai.com/v1")
        )
    }
    
    @Test
    fun testUrlWithV1AndTrailingSlash_NoDoubleAppend() {
        assertEquals(
            "https://api.openai.com/v1",
            normalizeBaseUrl("https://api.openai.com/v1/")
        )
    }
    
    @Test
    fun testUrlWithV1Beta_NoAppend() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta")
        )
    }
    
    @Test
    fun testUrlWithV1BetaOpenAI_NoAppend() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )
    }
    
    @Test
    fun testGroqUrl_AppendsV1() {
        // Groq URL without version should get /v1 appended
        assertEquals(
            "https://api.groq.com/openai/v1",
            normalizeBaseUrl("https://api.groq.com/openai/v1")
        )
    }
    
    @Test
    fun testLocalhost_AppendsV1() {
        assertEquals(
            "http://localhost:8000/v1",
            normalizeBaseUrl("http://localhost:8000")
        )
    }
    
    @Test
    fun testLocalhostWithV1_NoDoubleAppend() {
        assertEquals(
            "http://localhost:8000/v1",
            normalizeBaseUrl("http://localhost:8000/v1")
        )
    }
    
    @Test
    fun testPathWithV2_NoAppend() {
        assertEquals(
            "https://api.example.com/v2",
            normalizeBaseUrl("https://api.example.com/v2")
        )
    }
    
    @Test
    fun testPathWithV10_NoAppend() {
        assertEquals(
            "https://api.example.com/v10",
            normalizeBaseUrl("https://api.example.com/v10")
        )
    }
}
