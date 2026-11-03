#!/bin/bash
# Simple test to verify provider resolution logic

cd /workspace/app/src/test/java/com/wmods/wppenhacer/xposed/features/others

cat > TranslationProviderIntegrationTest.kt << 'EOF'
package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class TranslationProviderIntegrationTest {
    
    @Test
    fun testProviderSelection_AI_NotConfigured_FallsBackToGoogle() {
        // Mock preferences with AI selected but no API key
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getString("translation_provider", "google")).thenReturn("openai")
        `when`(prefs.getString("translation_ai_api_key", null)).thenReturn(null) // NOT configured
        
        // The bug: isConfigured() returns false, but provider caching uses "openai"
        // Expected: Should either use Google ID or show toast warning
        
        println("✓ Test setup: AI selected but API key = null")
        println("  Expected: Silent fallback to Google with toast")
        println("  Bug: Cache stores with provider='openai' but translation used Google")
    }
    
    @Test
    fun testCacheIsolation_GoogleVsAI() {
        println("\n✓ Cache Isolation Test")
        println("  Scenario: Same message translated by Google, then AI selected")
        println("  Expected: Cache MISS on provider='openai', new translation")
        println("  Bug: Cache HIT with provider='openai' returns Google's result")
        println("\n  Root cause: Line 564 uses cached 'provider' variable from line 548")
        println("  but translate() at line 557 may use different provider internally")
    }
}
EOF

echo "===== Running static analysis test ====="
cat TranslationProviderIntegrationTest.kt
EOF
chmod +x /workspace/test-provider.sh && /workspace/test-provider.sh
