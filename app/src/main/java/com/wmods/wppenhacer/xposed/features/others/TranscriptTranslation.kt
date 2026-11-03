package com.wmods.wppenhacer.xposed.features.others

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.xposed.core.WppCore
import java.util.concurrent.CompletableFuture

/**
 * Translates voice note transcripts when Live Translate is enabled for the chat.
 * Adds a visible Copy button to transcript views.
 */
object TranscriptTranslation {
    
    private val main = Handler(Looper.getMainLooper())
    
    /**
     * Enhance transcript view: add translation and copy button
     * Called from ConversationItemListener when transcript view is bound
     */
    fun enhanceTranscriptView(
        transcriptContainer: ViewGroup,
        transcriptText: String,
        chatJid: String?
    ) {
        if (transcriptText.isBlank()) return
        
        val config = getTranslateConfig(chatJid)
        if (!config.enabled || config.targetLang.isNullOrBlank()) {
            return
        }
        
        translateTranscriptAsync(transcriptText, chatJid, config.targetLang, config.perChatPrompt) { translation ->
            main.post {
                if (translation != null) {
                    showTranslationUnderTranscript(transcriptContainer, translation)
                }
                addCopyButton(transcriptContainer, transcriptText, translation)
            }
        }
    }
    
    private data class TranslateConfig(
        val enabled: Boolean,
        val targetLang: String?,
        val perChatPrompt: String?
    )
    
    private fun getTranslateConfig(chatJid: String?): TranslateConfig {
        val prefs = WppCore.getPrivPrefs()
        val key = "google_translation_v3_" + (chatJid ?: "default")
        val configJson = prefs.getString(key, null)
        
        val enabled = configJson != null
        var targetLang: String? = null
        
        if (configJson != null) {
            try {
                val obj = org.json.JSONObject(configJson)
                targetLang = obj.optString("targetLanguage", null)
            } catch (e: Exception) {
                //Ignore parse errors
            }
        }
        
        val promptKey = "google_translation_ai_prompt_$chatJid"
        val perChatPrompt = prefs.getString(promptKey, null)
        
        return TranslateConfig(enabled, targetLang, perChatPrompt)
    }
    
    private fun translateTranscriptAsync(
        text: String,
        chatJid: String?,
        targetLang: String,
        perChatPrompt: String?,
        callback: (String?) -> Unit
    ) {
        val translator = GoogleTranslate.getInstance()
        if (translator == null) {
            callback(null)
            return
        }
        
        CompletableFuture.runAsync {
            try {
                val result = translator.translateWithResult(
                    text = text,
                    languageSource = "auto",
                    languageDest = targetLang,
                    bypassCache = false,
                    customPrompt = perChatPrompt
                ).get()
                
                callback(result.text)
            } catch (e: Exception) {
                callback(null)
            }
        }
    }
    
    private fun showTranslationUnderTranscript(container: ViewGroup, translation: String) {
        val existing = container.findViewWithTag<TextView>("transcript_translation")
        if (existing != null) {
            existing.text = "Translation: $translation"
            return
        }
        
        val translationView = TextView(container.context).apply {
            tag = "transcript_translation"
            text = "Translation: $translation"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 8, 0, 0)
        }
        
        container.addView(translationView)
    }
    
    private fun addCopyButton(container: ViewGroup, transcript: String, translation: String?) {
        val existing = container.findViewWithTag<ImageView>("transcript_copy_button")
        if (existing != null) return
        
        val copyButton = TextView(container.context).apply {
            tag = "transcript_copy_button"
            text = "📋"
            textSize = 16f
            setPadding(12, 12, 12, 12)
            gravity = Gravity.CENTER
            setOnClickListener {
                val textToCopy = if (translation.isNullOrBlank()) {
                    transcript
                } else {
                    "$transcript\n\nTranslation: $translation"
                }
                copyToClipboard(context, textToCopy)
            }
        }
        
        container.addView(copyButton)
    }
    
    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Transcript", text)
            clipboard?.setPrimaryClip(clip)
            
            Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Silent fail
        }
    }
}
