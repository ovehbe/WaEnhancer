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
    private val openAIProvider by lazy { OpenAITranslationProvider(WppCore.getPrivPrefs()) }
    
    override fun doHook() {
        if (!xprefs.getBoolean("google_translate", false)) return
        // Independent of UnityMessageTranslation and its restricted language packs.
        GoogleTranslateChatUi(classLoader, ::translateWithProvider, ::getCurrentProvider).install()
        GoogleTranslateOutgoing(classLoader, xprefs, ::translateWithProvider).doHook()
    }
    
    private fun getCurrentProvider(): TranslationProvider {
        val prefs = WppCore.getPrivPrefs()
        val selectedProvider = TranslationSettings.getSelectedProvider(prefs)
        
        return when (selectedProvider) {
            TranslationSettings.PROVIDER_OPENAI -> {
                if (openAIProvider.isConfigured()) {
                    openAIProvider
                } else {
                    YukiLog.log("Translation: AI provider not configured, falling back to Google")
                    googleProvider
                }
            }
            else -> googleProvider
        }
    }
    
    private fun translateWithProvider(
        text: String?,
        languageSource: String,
        languageDest: String
    ): CompletableFuture<String?> {
        val provider = getCurrentProvider()
        val future = provider.translate(text, languageSource, languageDest)
        
        // Handle fallback if AI fails and fallback is enabled
        val prefs = WppCore.getPrivPrefs()
        val selectedProvider = TranslationSettings.getSelectedProvider(prefs)
        
        if (selectedProvider == TranslationSettings.PROVIDER_OPENAI && 
            TranslationSettings.shouldFallbackToGoogle(prefs)) {
            return future.exceptionally { error ->
                YukiLog.log("Translation: AI failed (${error.message}), falling back to Google")
                try {
                    googleProvider.translate(text, languageSource, languageDest).get()
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        return future
    }

    override fun getPluginName() = "Live Translate"
}
