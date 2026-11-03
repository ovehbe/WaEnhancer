package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.YukiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GoogleTranslateOutgoing(
    loader: ClassLoader,
    preferences: SharedPreferences,
    private val translate: (String?, String, String) -> CompletableFuture<String?>
) : Feature(loader, preferences) {

    private val main = Handler(Looper.getMainLooper())
    private val settings get() = WppCore.getPrivPrefs()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val renderedViews = WeakHashMap<TextView, String>()
    private val isTranslating = AtomicBoolean(false)
    private val injectedButtons = WeakHashMap<Activity, View>()
    
    private companion object {
        const val TAG = "[WaeOutTranslate]"
        const val PREF_PREFIX = "gtrans_out_orig_"
        const val MAX_STORED_ORIGINALS = 200
        const val TRANSLATION_TIMEOUT_SECONDS = 5L
    }
    
    private fun keyOutgoing(chat: String?) = "google_translation_outgoing_v1_" + (chat ?: "default")
    private fun keyIncludeOriginal() = "google_translation_include_original"
    
    private fun configOutgoing(chat: String?): GoogleTranslationConfigOutgoing {
        val local = chat?.let { settings.getString(keyOutgoing(it), null) }
        val global = settings.getString(keyOutgoing(null), null)
        return GoogleTranslateSettingsOutgoing.decode(local) 
            ?: GoogleTranslateSettingsOutgoing.decode(global) 
            ?: GoogleTranslationConfigOutgoing()
    }
    
    private fun includeOriginal(): Boolean {
        return settings.getBoolean(keyIncludeOriginal(), true)
    }
    
    private fun storeOriginalText(messageId: String, original: String) {
        scope.launch {
            try {
                val editor = settings.edit()
                val key = PREF_PREFIX + messageId
                editor.putString(key, original)
                
                val allKeys = settings.all.keys.filter { it.startsWith(PREF_PREFIX) }
                if (allKeys.size >= MAX_STORED_ORIGINALS) {
                    allKeys.sorted().take(allKeys.size - MAX_STORED_ORIGINALS + 1).forEach {
                        editor.remove(it)
                    }
                }
                
                editor.apply()
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Failed to store original: ${e.message}")
            }
        }
    }
    
    private fun getOriginalText(messageId: String): String? {
        return try {
            settings.getString(PREF_PREFIX + messageId, null)
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Failed to retrieve original: ${e.message}")
            null
        }
    }
    
    private fun rawJid(jid: Any?): String? = runCatching {
        GoogleTranslateSettings.normalizeChatId(
            ReflectionUtils.callMethod(jid, "getRawString") as? String
        )
    }.getOrNull()

    private fun chatId(jid: Any?): String? {
        val raw = rawJid(jid) ?: return null
        if (raw.endsWith("@g.us")) return raw
        return rawJid(WppCore.getPhoneJidFromUserJid(jid)) ?: raw
    }

    override fun doHook() {
        if (!xprefs.getBoolean("google_translate", false)) return
        
        hookConversationButton()
        installOutgoingRendering()
    }

    private fun hookConversationButton() {
        YukiLog.log("$TAG Installing conversation translate button injector")
        
        WppCore.addListenerActivity { activity, state ->
            if (activity.javaClass.simpleName == "Conversation" && 
                state == WppCore.ActivityChangeState.ChangeType.STARTED) {
                try {
                    onConversationStarted(activity)
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Failed to inject button: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun onConversationStarted(activity: Activity) {
        YukiLog.log("$TAG Conversation activity started")
        
        activity.window?.decorView?.post {
            try {
                val currentJid = WppCore.getCurrentUserJid()
                if (currentJid == null) {
                    YukiLog.log("$TAG No current JID")
                    return@post
                }
                
                val chat = chatId(currentJid.userJid)
                if (chat == null) {
                    YukiLog.log("$TAG Failed to resolve chat ID")
                    return@post
                }
                
                val config = configOutgoing(chat)
                if (!config.enabled) {
                    YukiLog.log("$TAG Outgoing translation not enabled for $chat")
                    return@post
                }
                
                YukiLog.log("$TAG Outgoing translation enabled for $chat, target=${config.target}")
                
                // Find entry EditText
                val entryEditText = activity.findViewById<EditText>(Utils.getID("entry", "id"))
                if (entryEditText == null) {
                    YukiLog.log("$TAG ERROR: Entry EditText not found")
                    return@post
                }
                
                YukiLog.log("$TAG Found entry EditText")
                
                // Inject translate button
                injectTranslateButton(activity, entryEditText, config, chat)
                
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Failed in onConversationStarted: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun injectTranslateButton(
        activity: Activity,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        if (injectedButtons.containsKey(activity)) {
            YukiLog.log("$TAG Button already injected for this activity")
            return
        }
        
        try {
            // Find the parent container of the entry EditText
            var parent = entryEditText.parent
            while (parent != null && parent !is LinearLayout) {
                parent = parent.parent
            }
            
            if (parent !is LinearLayout) {
                YukiLog.log("$TAG ERROR: Could not find LinearLayout parent for entry")
                return
            }
            
            YukiLog.log("$TAG Found parent container: ${parent.javaClass.simpleName}")
            
            // Create translate button
            val size = Utils.dipToPixels(40)
            val margin = Utils.dipToPixels(4)
            
            val translateButton = Button(activity).apply {
                text = "T"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#25D366")) // WhatsApp green
                contentDescription = "Translate"
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minHeight = 0
                
                setOnClickListener {
                    YukiLog.log("$TAG Translate button clicked")
                    handleTranslateClick(it, entryEditText, config)
                }
            }
            
            translateButton.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = margin
                rightMargin = margin
            }
            
            // Find best insertion position (after entry, before other buttons)
            val entryIndex = parent.indexOfChild(entryEditText)
            if (entryIndex >= 0) {
                parent.addView(translateButton, entryIndex + 1)
                injectedButtons[activity] = translateButton
                YukiLog.log("$TAG Translate button injected at position ${entryIndex + 1} in conversation input bar")
            } else {
                YukiLog.log("$TAG ERROR: Could not find entry index in parent")
            }
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Failed to inject button: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleTranslateClick(
        button: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing
    ) {
        if (!isTranslating.compareAndSet(false, true)) {
            YukiLog.log("$TAG Already translating, ignoring click")
            return
        }
        
        val originalText = entryEditText.text?.toString()
        if (originalText.isNullOrBlank()) {
            YukiLog.log("$TAG Empty text")
            isTranslating.set(false)
            Utils.showToast("Enter a message first", Toast.LENGTH_SHORT)
            return
        }
        
        if (originalText.length > 4000) {
            YukiLog.log("$TAG Text too long (${originalText.length} chars)")
            isTranslating.set(false)
            Utils.showToast("Message too long to translate", Toast.LENGTH_SHORT)
            return
        }
        
        YukiLog.log("$TAG Starting translation: \"${originalText.take(50)}...\"")
        
        // Show progress
        button.alpha = 0.5f
        button.isEnabled = false
        
        // Translate on background thread
        scope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val translated = translate(originalText, "auto", config.target)
                    .get(TRANSLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val duration = System.currentTimeMillis() - startTime
                
                main.post {
                    try {
                        if (translated.isNullOrBlank()) {
                            YukiLog.log("$TAG Translation returned empty after ${duration}ms")
                            Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                            return@post
                        }
                        
                        YukiLog.log("$TAG Translation success in ${duration}ms: \"${translated.take(50)}...\"")
                        
                        // Format message: translation, blank line, original in italics
                        val formattedText = if (includeOriginal()) {
                            StringBuilder(translated)
                                .append("\n\n")
                                .append("_")
                                .append(originalText)
                                .append("_")
                                .toString()
                        } else {
                            translated
                        }
                        
                        // Set formatted text
                        entryEditText.setText(formattedText)
                        entryEditText.setSelection(formattedText.length)
                        
                        YukiLog.log("$TAG Text formatted and set in EditText (includeOriginal=${includeOriginal()})")
                        
                    } catch (e: Exception) {
                        YukiLog.log("$TAG ERROR in success handler: ${e.message}")
                        Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                    } finally {
                        button.alpha = 1.0f
                        button.isEnabled = true
                        isTranslating.set(false)
                    }
                }
                
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                
                main.post {
                    try {
                        button.alpha = 1.0f
                        button.isEnabled = true
                        Utils.showToast("Translation failed: ${e.message}", Toast.LENGTH_SHORT)
                    } catch (ex: Exception) {
                        YukiLog.log("$TAG ERROR in failure handler: ${ex.message}")
                    } finally {
                        isTranslating.set(false)
                    }
                }
            }
        }
    }

    private fun installOutgoingRendering() {
        ConversationItemListener.conversationListeners.add(object :
            ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(
                fMessage: FMessageWpp,
                view: ViewGroup,
                position: Int,
                convertView: android.view.View?
            ) {
                if (!fMessage.key.isFromMe) return
                
                val messageId = fMessage.key.messageID
                val original = getOriginalText(messageId) ?: return
                
                val textView = findMessageTextView(view, fMessage.messageStr ?: return) ?: return
                
                if (renderedViews[textView] == messageId) return
                
                val translated = fMessage.messageStr ?: return
                if (original.trim() == translated.trim()) return
                
                val output = SpannableStringBuilder(original)
                    .append("\n\n")
                    .append("Sent as\n")
                    .append(translated)
                
                renderedViews[textView] = messageId
                textView.text = output
            }
        })
    }

    private fun findMessageTextView(root: ViewGroup, text: String): TextView? {
        val messageTextId = Utils.getID("message_text", "id")
        root.findViewById<TextView>(messageTextId)?.let { 
            if (it.text.toString() == text || renderedViews[it] != null) return it 
        }
        
        fun findTextViewRecursive(view: android.view.View): TextView? {
            if (view is TextView && view !is android.widget.EditText) {
                val viewText = view.text.toString()
                if (viewText == text || renderedViews[view] != null) {
                    return view
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    findTextViewRecursive(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        
        return findTextViewRecursive(root)
    }

    override fun getPluginName() = "Google Translate Outgoing"
}
