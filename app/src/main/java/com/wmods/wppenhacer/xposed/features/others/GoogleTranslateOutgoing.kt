package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
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
    private val bypassNextSend = AtomicBoolean(false)
    
    private companion object {
        const val TAG = "[WaeOutTranslate]"
        const val PREF_PREFIX = "gtrans_out_orig_"
        const val MAX_STORED_ORIGINALS = 200
        const val TRANSLATION_TIMEOUT_SECONDS = 5L
    }
    
    private fun keyOutgoing(chat: String?) = "google_translation_outgoing_v1_" + (chat ?: "default")
    
    private fun configOutgoing(chat: String?): GoogleTranslationConfigOutgoing {
        val local = chat?.let { settings.getString(keyOutgoing(it), null) }
        val global = settings.getString(keyOutgoing(null), null)
        return GoogleTranslateSettingsOutgoing.decode(local) 
            ?: GoogleTranslateSettingsOutgoing.decode(global) 
            ?: GoogleTranslationConfigOutgoing()
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
        
        hookConversationSendButton()
        installOutgoingRendering()
    }

    private fun hookConversationSendButton() {
        YukiLog.log("$TAG Installing conversation send button interceptor")
        
        WppCore.addListenerActivity { activity, state ->
            if (activity.javaClass.simpleName == "Conversation" && 
                state == WppCore.ActivityChangeState.ChangeType.STARTED) {
                try {
                    onConversationStarted(activity)
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Failed to hook conversation: ${e.message}")
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
                
                // Find entry EditText and send button
                val entryEditText = activity.findViewById<EditText>(Utils.getID("entry", "id"))
                if (entryEditText == null) {
                    YukiLog.log("$TAG ERROR: Entry EditText not found")
                    return@post
                }
                
                val sendButton = activity.findViewById<ImageButton>(Utils.getID("send", "id"))
                if (sendButton == null) {
                    YukiLog.log("$TAG ERROR: Send button not found")
                    return@post
                }
                
                YukiLog.log("$TAG Found entry and send button, installing interceptor")
                
                // Store original click listener
                val originalOnClickListener = try {
                    val listenerInfoMethod = View::class.java.getDeclaredMethod("getListenerInfo")
                    listenerInfoMethod.isAccessible = true
                    val listenerInfo = listenerInfoMethod.invoke(sendButton)
                    val mOnClickListenerField = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
                    mOnClickListenerField.isAccessible = true
                    mOnClickListenerField.get(listenerInfo) as? View.OnClickListener
                } catch (e: Exception) {
                    YukiLog.log("$TAG WARN: Could not get original listener: ${e.message}")
                    null
                }
                
                // Install intercepting listener
                sendButton.setOnClickListener { view ->
                    handleSendClick(view, entryEditText, config, chat, originalOnClickListener)
                }
                
                YukiLog.log("$TAG Interceptor installed successfully")
                
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Failed in onConversationStarted: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleSendClick(
        sendButton: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String,
        originalListener: View.OnClickListener?
    ) {
        try {
            // Check bypass flag
            if (bypassNextSend.compareAndSet(true, false)) {
                YukiLog.log("$TAG Bypass flag set, calling original listener")
                originalListener?.onClick(sendButton) ?: sendButton.performClick()
                return
            }
            
            // Check if already translating
            if (!isTranslating.compareAndSet(false, true)) {
                YukiLog.log("$TAG Already translating, ignoring click")
                return
            }
            
            val originalText = entryEditText.text?.toString()
            if (originalText.isNullOrBlank()) {
                YukiLog.log("$TAG Empty text, calling original listener")
                isTranslating.set(false)
                originalListener?.onClick(sendButton) ?: sendButton.performClick()
                return
            }
            
            if (originalText.length > 4000) {
                YukiLog.log("$TAG Text too long (${originalText.length} chars), calling original listener")
                isTranslating.set(false)
                originalListener?.onClick(sendButton) ?: sendButton.performClick()
                return
            }
            
            YukiLog.log("$TAG Send clicked, intercepting. Text: \"${originalText.take(50)}...\"")
            
            // Show progress (dim button)
            sendButton.alpha = 0.5f
            sendButton.isEnabled = false
            
            // Translate on background thread
            scope.launch {
                try {
                    val translated = translate(originalText, "auto", config.target)
                        .get(TRANSLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    
                    main.post {
                        try {
                            sendButton.alpha = 1.0f
                            sendButton.isEnabled = true
                            
                            if (translated.isNullOrBlank()) {
                                YukiLog.log("$TAG Translation returned empty")
                                Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                                isTranslating.set(false)
                                return@post
                            }
                            
                            YukiLog.log("$TAG Translation success: \"${translated.take(50)}...\"")
                            
                            // Set translated text in EditText
                            entryEditText.setText(translated)
                            
                            // Set bypass flag and trigger send
                            bypassNextSend.set(true)
                            isTranslating.set(false)
                            
                            YukiLog.log("$TAG Triggering real send")
                            originalListener?.onClick(sendButton) ?: sendButton.performClick()
                            
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR in success handler: ${e.message}")
                            isTranslating.set(false)
                        }
                    }
                    
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                    
                    main.post {
                        try {
                            sendButton.alpha = 1.0f
                            sendButton.isEnabled = true
                            Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                            isTranslating.set(false)
                        } catch (ex: Exception) {
                            YukiLog.log("$TAG ERROR in failure handler: ${ex.message}")
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR in handleSendClick: ${e.message}")
            e.printStackTrace()
            sendButton.alpha = 1.0f
            sendButton.isEnabled = true
            isTranslating.set(false)
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
