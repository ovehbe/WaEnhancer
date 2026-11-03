package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.features.others.translation.GoogleTranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.OpenAITranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationSettings
import com.wmods.wppenhacer.xposed.utils.YukiLog
import java.util.concurrent.CompletableFuture

class GoogleTranslate(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {
    
    private val googleProvider = GoogleTranslationProvider()
    private val openAIProvider by lazy { OpenAITranslationProvider(xprefs) }
    
    private companion object {
        const val TAG = "[WaeOutTranslate]"
        var lastToastTime = 0L
    }
    
    override fun doHook() {
        if (!xprefs.getBoolean("google_translate", false)) return
        // Independent of UnityMessageTranslation and its restricted language packs.
        GoogleTranslateChatUi(classLoader, ::translateWithProvider, ::getCurrentProvider, xprefs).install()
        GoogleTranslateOutgoing(classLoader, xprefs, ::translateWithProvider, ::getCurrentProvider).doHook()
    }
    
    private fun getCurrentProvider(): TranslationProvider {
        val selectedProvider = TranslationSettings.getSelectedProvider(xprefs)
        
        YukiLog.log("$TAG getCurrentProvider: selected=$selectedProvider")
        
        return when (selectedProvider) {
            TranslationSettings.PROVIDER_OPENAI -> {
                val isConfigured = openAIProvider.isConfigured()
                YukiLog.log("$TAG AI provider configured: $isConfigured")
                
                if (isConfigured) {
                    openAIProvider
                } else {
                    YukiLog.log("$TAG AI selected but not configured (no API key), using Google")
                    showConfigWarningOnce("AI provider selected but API key not set. Using Google.")
                    googleProvider
                }
            }
            else -> {
                YukiLog.log("$TAG Using Google provider")
                googleProvider
            }
        }
    }
    
    private fun showConfigWarningOnce(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 60000) { // Once per minute max
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    com.wmods.wppenhacer.xposed.core.FeatureLoader.mApp,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            lastToastTime = now
        }
    }
    
    private fun translateWithProvider(
        text: String?,
        languageSource: String,
        languageDest: String
    ): CompletableFuture<String?> {
        val provider = getCurrentProvider()
        val selectedProvider = TranslationSettings.getSelectedProvider(xprefs)
        
        YukiLog.log("$TAG translate: provider=${provider.getName()}, selected=$selectedProvider, text length=${text?.length ?: 0}")
        
        val future = provider.translate(text, languageSource, languageDest)
        
        // Handle fallback if AI fails and fallback is enabled
        if (selectedProvider == TranslationSettings.PROVIDER_OPENAI && 
            TranslationSettings.shouldFallbackToGoogle(xprefs)) {
            return future.exceptionally { error ->
                YukiLog.log("$TAG AI translation failed (${error.message}), falling back to Google")
                try {
                    googleProvider.translate(text, languageSource, languageDest).get()
                } catch (e: Exception) {
                    YukiLog.log("$TAG Google fallback also failed: ${e.message}")
                    null
                }
            }
        }
        
        return future
    }

    override fun getPluginName() = "Live Translate"
}
