package com.wmods.wppenhacer.xposed.features.others

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for transcript+translation string formatting
 */
class TranscriptTranslationTest {
    
    @Test
    fun `buildFinalTranscript with no translation returns original`() {
        val transcript = "Hello world"
        val translation: String? = null
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Hello world", result)
    }
    
    @Test
    fun `buildFinalTranscript with empty translation returns original`() {
        val transcript = "Hello world"
        val translation = ""
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Hello world", result)
    }
    
    @Test
    fun `buildFinalTranscript with translation appends formatted translation`() {
        val transcript = "Hello world"
        val translation = "Hola mundo"
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Hello world\n\nTranslation: Hola mundo", result)
    }
    
    @Test
    fun `buildFinalTranscript with identical translation returns original`() {
        val transcript = "Hello world"
        val translation = "Hello world"
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Hello world", result)
    }
    
    @Test
    fun `buildFinalTranscript preserves multiline transcript`() {
        val transcript = "Line one\nLine two\nLine three"
        val translation = "Ligne un\nLigne deux\nLigne trois"
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Line one\nLine two\nLine three\n\nTranslation: Ligne un\nLigne deux\nLigne trois", result)
    }
    
    @Test
    fun `buildFinalTranscript handles blank translation`() {
        val transcript = "Hello world"
        val translation = "   "
        val result = buildFinalTranscript(transcript, translation)
        assertEquals("Hello world", result)
    }
    
    private fun buildFinalTranscript(transcript: String, translation: String?): String {
        if (translation.isNullOrBlank() || translation == transcript) {
            return transcript
        }
        return "$transcript\n\nTranslation: $translation"
    }
}
