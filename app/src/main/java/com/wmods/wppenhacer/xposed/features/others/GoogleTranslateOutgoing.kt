package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
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
                
                // Find entry EditText
                val entryEditText = activity.findViewById<EditText>(Utils.getID("entry", "id"))
                if (entryEditText == null) {
                    YukiLog.log("$TAG ERROR: Entry EditText not found")
                    return@post
                }
                
                YukiLog.log("$TAG Found entry EditText")
                
                // Handle send button - may be ViewStub initially
                val sendViewId = Utils.getID("send", "id")
                val sendView = activity.findViewById<View>(sendViewId)
                
                if (sendView is ViewStub) {
                    YukiLog.log("$TAG Send is ViewStub, waiting for inflation")
                    sendView.setOnInflateListener { _, inflated ->
                        YukiLog.log("$TAG Send ViewStub inflated")
                        installInterceptorOnSend(inflated, entryEditText, config, chat)
                    }
                } else if (sendView != null) {
                    YukiLog.log("$TAG Send view already inflated")
                    installInterceptorOnSend(sendView, entryEditText, config, chat)
                } else {
                    YukiLog.log("$TAG ERROR: Send view not found")
                }
                
                // Hook Enter key for IME send
                installEnterKeyHandler(entryEditText, config, chat)
                
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Failed in onConversationStarted: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun installInterceptorOnSend(
        sendView: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        try {
            YukiLog.log("$TAG Installing interceptor on send view")
            
            // Get original click listener
            val originalOnClickListener = try {
                val listenerInfoMethod = View::class.java.getDeclaredMethod("getListenerInfo")
                listenerInfoMethod.isAccessible = true
                val listenerInfo = listenerInfoMethod.invoke(sendView)
                val mOnClickListenerField = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
                mOnClickListenerField.isAccessible = true
                mOnClickListenerField.get(listenerInfo) as? View.OnClickListener
            } catch (e: Exception) {
                YukiLog.log("$TAG WARN: Could not get original listener: ${e.message}")
                null
            }
            
            // Install intercepting listener
            sendView.setOnClickListener { view ->
                handleSendClick(view, entryEditText, config, chat, originalOnClickListener)
            }
            
            YukiLog.log("$TAG Interceptor installed successfully")
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Failed to install interceptor: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun installEnterKeyHandler(
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        try {
            // Hook Enter key - only if WhatsApp's "Enter is send" is enabled
            entryEditText.setOnEditorActionListener { v, actionId, event ->
                // Check if this is a send action
                val isSendAction = (event?.keyCode == KeyEvent.KEYCODE_ENTER && 
                                   event.action == KeyEvent.ACTION_DOWN) ||
                                   actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                
                if (isSendAction) {
                    YukiLog.log("$TAG Enter/IME send detected")
                    // Find send button and trigger our handler
                    val activity = WppCore.getCurrentActivity()
                    val sendView = activity?.findViewById<View>(Utils.getID("send", "id"))
                    if (sendView != null && sendView !is ViewStub) {
                        handleSendClick(sendView, v as EditText, config, chat, null)
                        true  // Consume event
                    } else {
                        false  // Let it through
                    }
                } else {
                    false
                }
            }
            YukiLog.log("$TAG Enter key handler installed")
        } catch (e: Exception) {
            YukiLog.log("$TAG WARN: Failed to install Enter handler: ${e.message}")
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
                originalListener?.onClick(sendButton) ?: sendButton.callOnClick()
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
                originalListener?.onClick(sendButton) ?: sendButton.callOnClick()
                return
            }
            
            if (originalText.length > 4000) {
                YukiLog.log("$TAG Text too long (${originalText.length} chars), calling original listener")
                isTranslating.set(false)
                originalListener?.onClick(sendButton) ?: sendButton.callOnClick()
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
                            if (translated.isNullOrBlank()) {
                                YukiLog.log("$TAG Translation returned empty")
                                Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                                return@post
                            }
                            
                            YukiLog.log("$TAG Translation success: \"${translated.take(50)}...\"")
                            
                            // Set translated text in EditText
                            entryEditText.setText(translated)
                            entryEditText.setSelection(translated.length)
                            
                            // Important: Post the send to ensure setText is committed
                            main.postDelayed({
                                try {
                                    YukiLog.log("$TAG Triggering real send")
                                    
                                    // Set bypass flag
                                    bypassNextSend.set(true)
                                    
                                    // Trigger send - use performClick to simulate real tap
                                    val sent = if (originalListener != null) {
                                        originalListener.onClick(sendButton)
                                        true
                                    } else {
                                        sendButton.performClick()
                                    }
                                    
                                    if (!sent) {
                                        YukiLog.log("$TAG WARN: performClick returned false")
                                    }
                                    
                                } catch (e: Exception) {
                                    YukiLog.log("$TAG ERROR in send trigger: ${e.message}")
                                    bypassNextSend.set(false)
                                } finally {
                                    isTranslating.set(false)
                                }
                            }, 100)  // Short delay to ensure EditText commits
                            
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR in success handler: ${e.message}")
                        } finally {
                            sendButton.alpha = 1.0f
                            sendButton.isEnabled = true
                        }
                    }
                    
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                    
                    main.post {
                        try {
                            sendButton.alpha = 1.0f
                            sendButton.isEnabled = true
                            Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                        } catch (ex: Exception) {
                            YukiLog.log("$TAG ERROR in failure handler: ${ex.message}")
                        } finally {
                            isTranslating.set(false)
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
