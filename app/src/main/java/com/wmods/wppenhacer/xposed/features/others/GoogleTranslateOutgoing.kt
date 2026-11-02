package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
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

class GoogleTranslateOutgoing(
    loader: ClassLoader,
    preferences: SharedPreferences,
    private val translate: (String?, String, String) -> CompletableFuture<String?>
) : Feature(loader, preferences) {

    private val main = Handler(Looper.getMainLooper())
    private val settings get() = WppCore.getPrivPrefs()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val renderedViews = WeakHashMap<TextView, String>()
    
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
        
        hookActionUserSend()
        installOutgoingRendering()
    }

    private fun hookActionUserSend() {
        try {
            val actionUserClass = Unobfuscator.loadActionUser(classLoader)
            
            // Find the send method that takes (String message, List<UserJid> recipients, ...)
            val sendMethod = ReflectionUtils.findMethodUsingFilter(actionUserClass) { method ->
                List::class.java.isAssignableFrom(method.returnType) &&
                ReflectionUtils.findIndexOfType(method.parameterTypes, String::class.java) != -1 &&
                ReflectionUtils.findIndexOfType(method.parameterTypes, List::class.java) != -1
            }
            
            if (sendMethod == null) {
                YukiLog.log("$TAG ERROR: Send method not found in ActionUser")
                return
            }
            
            YukiLog.log("$TAG Hook installed on ActionUser send method")
            
            val stringIndex = ReflectionUtils.findIndexOfType(sendMethod.parameterTypes, String::class.java)
            val listIndex = ReflectionUtils.findIndexOfType(sendMethod.parameterTypes, List::class.java)
            
            sendMethod.hook {
                before {
                    try {
                        YukiLog.log("$TAG Hook fired - thread=${Thread.currentThread().name}")
                        
                        val originalText = args[stringIndex] as? String
                        if (originalText.isNullOrBlank()) {
                            YukiLog.log("$TAG Message text is empty, skipping")
                            return@before
                        }
                        
                        if (originalText.length > 4000) {
                            YukiLog.log("$TAG Message too long (${originalText.length} chars), skipping")
                            return@before
                        }
                        
                        @Suppress("UNCHECKED_CAST")
                        val recipients = args[listIndex] as? List<Any> ?: run {
                            YukiLog.log("$TAG No recipients found")
                            return@before
                        }
                        
                        if (recipients.isEmpty()) {
                            YukiLog.log("$TAG Empty recipient list")
                            return@before
                        }
                        
                        // Get first recipient JID
                        val recipientJid = recipients.firstOrNull() ?: return@before
                        
                        val chat = chatId(recipientJid)
                        if (chat == null) {
                            YukiLog.log("$TAG Failed to resolve chat ID")
                            return@before
                        }
                        
                        YukiLog.log("$TAG Resolved chat: $chat")
                        
                        val config = configOutgoing(chat)
                        YukiLog.log("$TAG Config: enabled=${config.enabled}, target=${config.target}")
                        
                        if (!config.enabled) {
                            YukiLog.log("$TAG Outgoing translation not enabled")
                            return@before
                        }
                        
                        YukiLog.log("$TAG Translating: \"${originalText.take(50)}...\" to ${config.target}")
                        
                        // Translate on background thread with timeout
                        val translated = try {
                            translate(originalText, "auto", config.target).get(TRANSLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                            main.post {
                                Utils.showToast("Translation failed. Message not sent.", Toast.LENGTH_SHORT)
                            }
                            result = null
                            return@before
                        }
                        
                        if (translated.isNullOrBlank()) {
                            YukiLog.log("$TAG ERROR: Translation returned empty")
                            main.post {
                                Utils.showToast("Translation failed. Message not sent.", Toast.LENGTH_SHORT)
                            }
                            result = null
                            return@before
                        }
                        
                        YukiLog.log("$TAG Translation: \"${translated.take(50)}...\"")
                        
                        // Replace the message text with translated text
                        args[stringIndex] = translated
                        
                        // Note: We could store original for rendering later, but that requires
                        // hooking message insertion to capture the message key. For now, send translated.
                        YukiLog.log("$TAG SUCCESS: Sending translated message")
                        
                    } catch (e: Exception) {
                        YukiLog.log("$TAG ERROR: Hook execution failed: ${e.javaClass.simpleName}: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR: Hook setup failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
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
