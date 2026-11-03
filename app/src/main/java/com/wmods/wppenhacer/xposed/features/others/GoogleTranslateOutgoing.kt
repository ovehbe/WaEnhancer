package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.widget.EditText
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
import java.lang.reflect.Field
import java.lang.reflect.Method
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
    
    private val hookedSendViews = WeakHashMap<View, Boolean>()
    private val hookedEditTexts = WeakHashMap<EditText, Boolean>()
    
    private var performClickMethod: Method? = null
    private var onKeyDownMethod: Method? = null
    
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
        
        try {
            performClickMethod = View::class.java.getDeclaredMethod("performClick")
            onKeyDownMethod = View::class.java.getDeclaredMethod("onKeyDown", Int::class.javaPrimitiveType, KeyEvent::class.java)
            YukiLog.log("$TAG Cached reflection methods")
        } catch (e: Exception) {
            YukiLog.log("$TAG WARN: Failed to cache methods: ${e.message}")
        }
        
        hookConversationViews()
        installOutgoingRendering()
    }

    private fun hookConversationViews() {
        YukiLog.log("$TAG Installing conversation view hooks")
        
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
                
                // Hook EditText events
                hookEditTextEvents(entryEditText, config, chat)
                
                // Handle send button - may be ViewStub initially
                val sendViewId = Utils.getID("send", "id")
                val sendView = activity.findViewById<View>(sendViewId)
                
                if (sendView is ViewStub) {
                    YukiLog.log("$TAG Send is ViewStub, waiting for inflation")
                    sendView.setOnInflateListener { _, inflated ->
                        YukiLog.log("$TAG Send ViewStub inflated")
                        hookSendViewEvents(inflated, entryEditText, config, chat)
                    }
                } else if (sendView != null) {
                    YukiLog.log("$TAG Send view already inflated")
                    hookSendViewEvents(sendView, entryEditText, config, chat)
                } else {
                    YukiLog.log("$TAG ERROR: Send view not found")
                }
                
            } catch (e: Exception) {
                YukiLog.log("$TAG ERROR: Failed in onConversationStarted: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun hookSendViewEvents(
        sendView: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        if (hookedSendViews.containsKey(sendView)) {
            YukiLog.log("$TAG Send view already hooked")
            return
        }
        
        YukiLog.log("$TAG Hooking send view events (class=${sendView.javaClass.simpleName}, id=${sendView.id})")
        
        try {
            // Install touch listener to intercept all touches
            sendView.setOnTouchListener { v, event ->
                YukiLog.log("$TAG >>> OnTouchListener action=${event.action} (${motionActionToString(event.action)})")
                
                if (event.action == MotionEvent.ACTION_UP) {
                    YukiLog.log("$TAG Touch ACTION_UP detected, checking intercept")
                    if (handleSendIntercept(v, entryEditText, config, chat)) {
                        YukiLog.log("$TAG Intercepted, consuming event")
                        true
                    } else {
                        YukiLog.log("$TAG Not intercepted, allowing event")
                        false
                    }
                } else {
                    false
                }
            }
            
            // Also wrap any existing click listener
            wrapExistingClickListener(sendView, entryEditText, config, chat)
            
            // Monitor for listener changes
            val observer = sendView.viewTreeObserver
            observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    wrapExistingClickListener(sendView, entryEditText, config, chat)
                }
            })
            
            hookedSendViews[sendView] = true
            YukiLog.log("$TAG Send view event hooks installed")
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Failed to hook send view: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun wrapExistingClickListener(
        sendView: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        try {
            val originalListener = getClickListener(sendView)
            if (originalListener != null) {
                YukiLog.log("$TAG Found existing click listener: ${originalListener.javaClass.simpleName}")
                
                sendView.setOnClickListener { v ->
                    YukiLog.log("$TAG >>> OnClickListener called")
                    
                    if (handleSendIntercept(v, entryEditText, config, chat)) {
                        YukiLog.log("$TAG Intercepted, NOT calling original listener")
                    } else {
                        YukiLog.log("$TAG Not intercepted, calling original listener")
                        originalListener.onClick(v)
                    }
                }
            }
        } catch (e: Exception) {
            YukiLog.log("$TAG WARN: Failed to wrap click listener: ${e.message}")
        }
    }

    private fun getClickListener(view: View): View.OnClickListener? {
        return try {
            val listenerInfoMethod = View::class.java.getDeclaredMethod("getListenerInfo")
            listenerInfoMethod.isAccessible = true
            val listenerInfo = listenerInfoMethod.invoke(view)
            
            val mOnClickListenerField = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
            mOnClickListenerField.isAccessible = true
            mOnClickListenerField.get(listenerInfo) as? View.OnClickListener
        } catch (e: Exception) {
            null
        }
    }

    private fun hookEditTextEvents(
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ) {
        if (hookedEditTexts.containsKey(entryEditText)) {
            YukiLog.log("$TAG EditText already hooked")
            return
        }
        
        YukiLog.log("$TAG Hooking EditText events")
        
        try {
            // Override onKeyDown
            entryEditText.setOnKeyListener { v, keyCode, event ->
                YukiLog.log("$TAG >>> OnKeyListener keyCode=$keyCode action=${event.action}")
                
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    YukiLog.log("$TAG ENTER key DOWN detected")
                    
                    val activity = WppCore.getCurrentActivity()
                    val sendView = activity?.findViewById<View>(Utils.getID("send", "id"))
                    if (sendView != null && sendView !is ViewStub) {
                        if (handleSendIntercept(sendView, v as EditText, config, chat)) {
                            YukiLog.log("$TAG Intercepted ENTER, consuming event")
                            return@setOnKeyListener true
                        }
                    }
                }
                
                false
            }
            
            // Also hook editor action
            entryEditText.setOnEditorActionListener { v, actionId, event ->
                YukiLog.log("$TAG >>> OnEditorActionListener actionId=$actionId")
                
                val isSendAction = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                                   (event?.keyCode == KeyEvent.KEYCODE_ENTER && 
                                    event.action == KeyEvent.ACTION_DOWN)
                
                if (isSendAction) {
                    YukiLog.log("$TAG IME send action detected")
                    
                    val activity = WppCore.getCurrentActivity()
                    val sendView = activity?.findViewById<View>(Utils.getID("send", "id"))
                    if (sendView != null && sendView !is ViewStub) {
                        if (handleSendIntercept(sendView, v as EditText, config, chat)) {
                            YukiLog.log("$TAG Intercepted IME send, consuming event")
                            return@setOnEditorActionListener true
                        }
                    }
                }
                
                false
            }
            
            hookedEditTexts[entryEditText] = true
            YukiLog.log("$TAG EditText event hooks installed")
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Failed to hook EditText: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun motionActionToString(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    private fun handleSendIntercept(
        sendView: View,
        entryEditText: EditText,
        config: GoogleTranslationConfigOutgoing,
        chat: String
    ): Boolean {
        try {
            // Check bypass flag
            if (bypassNextSend.compareAndSet(true, false)) {
                YukiLog.log("$TAG Bypass flag set, allowing send")
                return false
            }
            
            // Check if already translating
            if (!isTranslating.compareAndSet(false, true)) {
                YukiLog.log("$TAG Already translating, blocking send")
                return true
            }
            
            val originalText = entryEditText.text?.toString()
            if (originalText.isNullOrBlank()) {
                YukiLog.log("$TAG Empty text, allowing send")
                isTranslating.set(false)
                return false
            }
            
            if (originalText.length > 4000) {
                YukiLog.log("$TAG Text too long (${originalText.length} chars), allowing send")
                isTranslating.set(false)
                return false
            }
            
            YukiLog.log("$TAG Send intercepted! Text: \"${originalText.take(50)}...\"")
            
            // Show progress
            sendView.alpha = 0.5f
            sendView.isEnabled = false
            
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
                            
                            // Set translated text
                            entryEditText.setText(translated)
                            entryEditText.setSelection(translated.length)
                            
                            // Post the real send with delay
                            main.postDelayed({
                                try {
                                    YukiLog.log("$TAG Triggering real send via performClick")
                                    bypassNextSend.set(true)
                                    val sent = sendView.performClick()
                                    YukiLog.log("$TAG performClick returned: $sent")
                                } catch (e: Exception) {
                                    YukiLog.log("$TAG ERROR in send trigger: ${e.message}")
                                    bypassNextSend.set(false)
                                } finally {
                                    isTranslating.set(false)
                                }
                            }, 150)
                            
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR in success handler: ${e.message}")
                            isTranslating.set(false)
                        } finally {
                            sendView.alpha = 1.0f
                            sendView.isEnabled = true
                        }
                    }
                    
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                    
                    main.post {
                        try {
                            sendView.alpha = 1.0f
                            sendView.isEnabled = true
                            Utils.showToast("Translation failed", Toast.LENGTH_SHORT)
                        } catch (ex: Exception) {
                            YukiLog.log("$TAG ERROR in failure handler: ${ex.message}")
                        } finally {
                            isTranslating.set(false)
                        }
                    }
                }
            }
            
            return true
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR in handleSendIntercept: ${e.message}")
            e.printStackTrace()
            sendView.alpha = 1.0f
            sendView.isEnabled = true
            isTranslating.set(false)
            return false
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
