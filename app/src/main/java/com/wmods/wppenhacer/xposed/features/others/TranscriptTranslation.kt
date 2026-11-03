package com.wmods.wppenhacer.xposed.features.others

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.xposed.utils.YukiLog

/**
 * Translates voice note transcripts when Live Translate is enabled for the chat.
 * Adds a Copy button to transcript views.
 */
object TranscriptTranslation {
    
    private const val TAG = "[TranscriptTranslate]"
    
    /**
     * Translate transcript text for a chat if Live Translate is enabled
     */
    fun translateTranscript(
        transcriptText: String,
        chatJid: String?,
        targetLang: String?
    ): String? {
        if (transcriptText.isBlank() || targetLang.isNullOrBlank()) {
            return null
        }
        
        val translator = GoogleTranslate.getInstance() ?: run {
            YukiLog.log("$TAG Translator instance not available")
            return null
        }
        
        return try {
            val result = translator.translateWithResult(
                text = transcriptText,
                languageSource = "auto",
                languageDest = targetLang,
                bypassCache = false,
                customPrompt = null
            ).get()
            
            result.text
        } catch (e: Exception) {
            YukiLog.log("$TAG Translation failed: ${e.message}")
            null
        }
    }
    
    /**
     * Add copy button to transcript view
     */
    fun addCopyButton(
        transcriptView: TextView,
        transcriptText: String,
        translationText: String?
    ) {
        val context = transcriptView.context ?: return
        
        transcriptView.setOnLongClickListener {
            val textToCopy = if (translationText.isNullOrBlank()) {
                transcriptText
            } else {
                "$transcriptText\n\nTranslation: $translationText"
            }
            
            copyToClipboard(context, textToCopy)
            true
        }
    }
    
    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Transcript", text)
            clipboard?.setPrimaryClip(clip)
            
            Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            YukiLog.log("$TAG Copy failed: ${e.message}")
        }
    }
}
