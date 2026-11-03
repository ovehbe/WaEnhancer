package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.features.others.translation.GoogleTranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.OpenAITranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationResult
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationSettings
import com.wmods.wppenhacer.xposed.utils.YukiLog
import java.util.concurrent.CompletableFuture

class GoogleTranslate(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {
    
    private val googleProvider = GoogleTranslationProvider()
    private val openAIProvider by lazy { OpenAITranslationProvider(xprefs) }
    
    private companion object {
        const val TAG = "[WaeOutTranslate]"
        var lastConfigWarningTime = 0L
        var lastAiFailureTime = 0L
        var aiFailureShown = false
    }
    
    override fun doHook() {
        if (!xprefs.getBoolean("google_translate", false)) return
        // Independent of UnityMessageTranslation and its restricted language packs.
        GoogleTranslateChatUi(classLoader, ::translateExtractText, ::getCurrentProvider, xprefs, ::translateWithResult).install()
        GoogleTranslateOutgoing(classLoader, xprefs, ::translateExtractText, ::getCurrentProvider).doHook()
    }
    
    // Wrapper that extracts text from TranslationResult for compatibility
    private fun translateExtractText(
        text: String?,
        languageSource: String,
        languageDest: String
    ): CompletableFuture<String?> {
        return translateWithResult(text, languageSource, languageDest)
            .thenApply { it.text }
    }
    
    private fun getCurrentProvider(): TranslationProvider {
        val selectedProvider = TranslationSettings.getSelectedProvider(xprefs)
        
        return when (selectedProvider) {
            TranslationSettings.PROVIDER_OPENAI -> {
                val apiKey = xprefs.getString("translation_ai_api_key", null)
                val isConfigured = !apiKey.isNullOrBlank()
                
                YukiLog.log("$TAG AI selected | API key present: $isConfigured (keyLen=${apiKey?.length ?: 0})")
                
                if (isConfigured) {
                    openAIProvider
                } else {
                    showConfigWarningOnce("AI Translation: API key not configured. Using Google fallback.")
                    googleProvider
                }
            }
            else -> {
                googleProvider
            }
        }
    }
    
    private fun showConfigWarningOnce(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastConfigWarningTime > 60000) { // Once per minute max
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    com.wmods.wppenhacer.xposed.core.FeatureLoader.mApp,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            lastConfigWarningTime = now
        }
    }
    
    private fun showAiFailureOnce(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastAiFailureTime > 60000) { // Rate-limited: once per 60 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    com.wmods.wppenhacer.xposed.core.FeatureLoader.mApp,
                    "AI Translation Failed: $message",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            lastAiFailureTime = now
        }
    }
    
    private fun translateWithResult(
        text: String?,
        languageSource: String,
        languageDest: String
    ): CompletableFuture<TranslationResult> {
        val provider = getCurrentProvider()
        val future = provider.translateWithResult(text, languageSource, languageDest)
        
        val selectedProvider = TranslationSettings.getSelectedProvider(xprefs)
        if (selectedProvider == TranslationSettings.PROVIDER_OPENAI && 
            TranslationSettings.shouldFallbackToGoogle(xprefs)) {
            return future.exceptionally { error ->
                showAiFailureOnce(error.message ?: "Unknown error")
                try {
                    val googleText = googleProvider.translate(text, languageSource, languageDest).get()
                    YukiLog.log("$TAG ✓ Google fallback succeeded")
                    TranslationResult.fallback(googleText)
                } catch (e: Exception) {
                    YukiLog.log("$TAG ✗ Google fallback also failed: ${e.message}")
                    TranslationResult.fallback(null)
                }
            }
        }
        
        return future
    }

    override fun getPluginName() = "Live Translate"
}
