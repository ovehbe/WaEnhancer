package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.features.others.translation.GoogleTranslationProvider
import com.wmods.wppenhacer.xposed.features.others.translation.TranslationProvider
import java.util.concurrent.CompletableFuture

class GoogleTranslate(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {
    
    private val googleProvider = GoogleTranslationProvider()
    
    override fun doHook() {
        if (!xprefs.getBoolean("google_translate", false)) return
        // Independent of UnityMessageTranslation and its restricted language packs.
        GoogleTranslateChatUi(classLoader, ::translateWithProvider).install()
        GoogleTranslateOutgoing(classLoader, xprefs, ::translateWithProvider).doHook()
    }
    
    private fun translateWithProvider(
        text: String?,
        languageSource: String,
        languageDest: String
    ): CompletableFuture<String?> {
        return googleProvider.translate(text, languageSource, languageDest)
    }

    override fun getPluginName() = "Google Translate"
}
