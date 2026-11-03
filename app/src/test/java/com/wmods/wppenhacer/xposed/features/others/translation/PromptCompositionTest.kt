package com.wmods.wppenhacer.xposed.features.others.translation

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AI prompt composition and cache key hashing
 */
class PromptCompositionTest {
    
    @Test
    fun `buildEffectivePrompt with empty global and per-chat returns empty`() {
        val result = buildPrompt(null, null)
        assertEquals("", result)
    }
    
    @Test
    fun `buildEffectivePrompt with only global prompt`() {
        val global = "Be formal"
        val result = buildPrompt(global, null)
        assertEquals("Be formal", result)
    }
    
    @Test
    fun `buildEffectivePrompt with only per-chat prompt`() {
        val perChat = "Use casual language"
        val result = buildPrompt(null, perChat)
        assertEquals("Use casual language", result)
    }
    
    @Test
    fun `buildEffectivePrompt with both prompts combines them`() {
        val global = "Be polite"
        val perChat = "Avoid slang"
        val result = buildPrompt(global, perChat)
        assertEquals("Be polite\nAvoid slang", result)
    }
    
    @Test
    fun `buildEffectivePrompt trims whitespace`() {
        val global = "  Global prompt  "
        val perChat = "  Per-chat prompt  "
        val result = buildPrompt(global, perChat)
        assertEquals("Global prompt\nPer-chat prompt", result)
    }
    
    @Test
    fun `buildEffectivePrompt ignores blank prompts`() {
        val result1 = buildPrompt("", "Valid")
        assertEquals("Valid", result1)
        
        val result2 = buildPrompt("Valid", "  ")
        assertEquals("Valid", result2)
        
        val result3 = buildPrompt("   ", "  ")
        assertEquals("", result3)
    }
    
    @Test
    fun `hashPrompt returns null for null input`() {
        val hash = TranslationCache.hashPrompt(null)
        assertNull(hash)
    }
    
    @Test
    fun `hashPrompt returns null for blank input`() {
        val hash1 = TranslationCache.hashPrompt("")
        assertNull(hash1)
        
        val hash2 = TranslationCache.hashPrompt("   ")
        assertNull(hash2)
    }
    
    @Test
    fun `hashPrompt returns 8-character hex string`() {
        val hash = TranslationCache.hashPrompt("test prompt")
        assertNotNull(hash)
        assertEquals(8, hash!!.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{8}")))
    }
    
    @Test
    fun `hashPrompt is deterministic`() {
        val prompt = "Be formal and professional"
        val hash1 = TranslationCache.hashPrompt(prompt)
        val hash2 = TranslationCache.hashPrompt(prompt)
        assertEquals(hash1, hash2)
    }
    
    @Test
    fun `hashPrompt different for different inputs`() {
        val hash1 = TranslationCache.hashPrompt("prompt one")
        val hash2 = TranslationCache.hashPrompt("prompt two")
        assertNotEquals(hash1, hash2)
    }
    
    @Test
    fun `hashPrompt examples`() {
        val hash1 = TranslationCache.hashPrompt("Be formal")
        val hash2 = TranslationCache.hashPrompt("Use casual language")
        
        assertNotNull(hash1)
        assertNotNull(hash2)
        assertNotEquals(hash1, hash2)
        
        println("Hash('Be formal') = $hash1")
        println("Hash('Use casual language') = $hash2")
    }
    
    @Test
    fun `cacheKey includes promptHash when present`() {
        val provider = "openai"
        val promptHash = "abc12345"
        
        val key = buildProviderKey(provider, promptHash)
        assertEquals("openai:abc12345", key)
    }
    
    @Test
    fun `cacheKey excludes promptHash when null`() {
        val provider = "openai"
        val key = buildProviderKey(provider, null)
        assertEquals("openai", key)
    }
    
    @Test
    fun `cacheKey excludes promptHash when blank`() {
        val provider = "google"
        val key = buildProviderKey(provider, "")
        assertEquals("google", key)
    }
    
    // Helper methods mimicking the actual implementation
    private fun buildPrompt(global: String?, perChat: String?): String {
        val parts = mutableListOf<String>()
        
        if (!global.isNullOrBlank()) {
            parts.add(global.trim())
        }
        
        if (!perChat.isNullOrBlank()) {
            parts.add(perChat.trim())
        }
        
        return if (parts.isEmpty()) "" else parts.joinToString("\n")
    }
    
    private fun buildProviderKey(provider: String, promptHash: String?): String {
        return if (promptHash.isNullOrBlank()) {
            provider
        } else {
            "$provider:$promptHash"
        }
    }
}
