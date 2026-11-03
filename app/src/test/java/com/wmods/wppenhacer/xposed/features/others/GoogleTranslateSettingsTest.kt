package com.wmods.wppenhacer.xposed.features.others

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for GoogleTranslateSettings encoding/decoding
 */
class GoogleTranslateSettingsTest {
    
    @Test
    fun `encode and decode config with enabled true`() {
        val config = GoogleTranslationConfig(
            enabled = true,
            source = "auto",
            target = "es"
        )
        
        val encoded = config.encode()
        assertEquals("on|auto|es", encoded)
        
        val decoded = GoogleTranslateSettings.decode(encoded)
        assertNotNull(decoded)
        assertEquals(true, decoded!!.enabled)
        assertEquals("auto", decoded.source)
        assertEquals("es", decoded.target)
    }
    
    @Test
    fun `encode and decode config with enabled false`() {
        val config = GoogleTranslationConfig(
            enabled = false,
            source = "en",
            target = "fr"
        )
        
        val encoded = config.encode()
        assertEquals("off|en|fr", encoded)
        
        val decoded = GoogleTranslateSettings.decode(encoded)
        assertNotNull(decoded)
        assertEquals(false, decoded!!.enabled)
        assertEquals("en", decoded.source)
        assertEquals("fr", decoded.target)
    }
    
    @Test
    fun `decode returns null for invalid format`() {
        assertNull(GoogleTranslateSettings.decode(null))
        assertNull(GoogleTranslateSettings.decode(""))
        assertNull(GoogleTranslateSettings.decode("invalid"))
        assertNull(GoogleTranslateSettings.decode("on|es"))
        assertNull(GoogleTranslateSettings.decode("on|invalid|es"))
    }
    
    @Test
    fun `legacy decode converts old format`() {
        val legacy = GoogleTranslateSettings.legacy("es")
        assertNotNull(legacy)
        assertEquals(true, legacy!!.enabled)
        assertEquals("auto", legacy.source)
        assertEquals("es", legacy.target)
    }
    
    @Test
    fun `legacy decode off creates disabled config`() {
        val legacy = GoogleTranslateSettings.legacy("off")
        assertNotNull(legacy)
        assertEquals(false, legacy!!.enabled)
    }
    
    @Test
    fun `resolve prioritizes local over global`() {
        val local = "on|en|es"
        val global = "on|auto|fr"
        
        val resolved = GoogleTranslateSettings.resolve(local, global, null, null)
        assertEquals(true, resolved.enabled)
        assertEquals("en", resolved.source)
        assertEquals("es", resolved.target)
    }
    
    @Test
    fun `resolve falls back to global when local is null`() {
        val global = "on|auto|de"
        
        val resolved = GoogleTranslateSettings.resolve(null, global, null, null)
        assertEquals(true, resolved.enabled)
        assertEquals("auto", resolved.source)
        assertEquals("de", resolved.target)
    }
    
    @Test
    fun `resolve uses legacy when new format unavailable`() {
        val legacyLocal = "it"
        
        val resolved = GoogleTranslateSettings.resolve(null, null, legacyLocal, null)
        assertEquals(true, resolved.enabled)
        assertEquals("auto", resolved.source)
        assertEquals("it", resolved.target)
    }
    
    @Test
    fun `resolve returns default when all null`() {
        val resolved = GoogleTranslateSettings.resolve(null, null, null, null)
        assertEquals(false, resolved.enabled)
        assertEquals("auto", resolved.source)
        assertEquals("en", resolved.target)
    }
    
    @Test
    fun `normalizeChatId preserves group IDs`() {
        assertEquals("123456789@g.us", GoogleTranslateSettings.normalizeChatId("123456789@g.us"))
        assertEquals("123-456@g.us", GoogleTranslateSettings.normalizeChatId("123-456@g.us"))
    }
    
    @Test
    fun `normalizeChatId preserves phone numbers`() {
        assertEquals("1234567890@s.whatsapp.net", 
            GoogleTranslateSettings.normalizeChatId("1234567890@s.whatsapp.net"))
    }
    
    @Test
    fun `normalizeChatId strips device suffix`() {
        assertEquals("1234567890@s.whatsapp.net",
            GoogleTranslateSettings.normalizeChatId("1234567890.0:1@s.whatsapp.net"))
    }
    
    @Test
    fun `normalizeChatId returns null for invalid JIDs`() {
        assertNull(GoogleTranslateSettings.normalizeChatId(null))
        assertNull(GoogleTranslateSettings.normalizeChatId(""))
        assertNull(GoogleTranslateSettings.normalizeChatId("@s.whatsapp.net"))
        assertNull(GoogleTranslateSettings.normalizeChatId("123@broadcast"))
    }
}
