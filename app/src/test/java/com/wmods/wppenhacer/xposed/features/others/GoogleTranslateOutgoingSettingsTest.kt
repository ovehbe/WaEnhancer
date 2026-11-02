package com.wmods.wppenhacer.xposed.features.others

import org.junit.Assert.*
import org.junit.Test

class GoogleTranslateOutgoingSettingsTest {
    
    @Test
    fun outgoingConfigRoundTrip() {
        val config = GoogleTranslationConfigOutgoing(true, "es")
        assertEquals(config, GoogleTranslateSettingsOutgoing.decode(config.encode()))
    }
    
    @Test
    fun outgoingDisabledConfig() {
        val config = GoogleTranslationConfigOutgoing(false, "fr")
        assertEquals("off|fr", config.encode())
        assertEquals(config, GoogleTranslateSettingsOutgoing.decode("off|fr"))
    }
    
    @Test
    fun outgoingEnabledConfig() {
        val config = GoogleTranslationConfigOutgoing(true, "de")
        assertEquals("on|de", config.encode())
        assertEquals(config, GoogleTranslateSettingsOutgoing.decode("on|de"))
    }
    
    @Test
    fun outgoingInvalidSettings() {
        assertNull(GoogleTranslateSettingsOutgoing.decode("on"))
        assertNull(GoogleTranslateSettingsOutgoing.decode("on|"))
        assertNull(GoogleTranslateSettingsOutgoing.decode("|es"))
        assertNull(GoogleTranslateSettingsOutgoing.decode("invalid|es"))
        assertNull(GoogleTranslateSettingsOutgoing.decode("on|invalid"))
        assertNull(GoogleTranslateSettingsOutgoing.decode("on|auto"))
    }
    
    @Test
    fun outgoingDefaultConfig() {
        val config = GoogleTranslationConfigOutgoing()
        assertFalse(config.enabled)
        assertEquals("en", config.target)
    }
    
    @Test
    fun outgoingAllValidLanguages() {
        GoogleTranslateLanguages.entries.forEach { (code, _) ->
            val config = GoogleTranslationConfigOutgoing(true, code)
            assertEquals(config, GoogleTranslateSettingsOutgoing.decode(config.encode()))
        }
    }
    
    @Test
    fun outgoingNoSourceLanguage() {
        val config = GoogleTranslationConfigOutgoing(true, "ja")
        val encoded = config.encode()
        assertFalse(encoded.contains("auto"))
        assertTrue(encoded.startsWith("on|"))
    }
}
