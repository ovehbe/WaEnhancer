package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.luckypray.dexkit.query.enums.StringMatchType
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
        const val TAG = "WaeOutTranslate"
        const val PREF_PREFIX = "gtrans_out_orig_"
        const val MAX_STORED_ORIGINALS = 200
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
                Log.d(TAG, "Stored original for message $messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store original: ${e.message}", e)
            }
        }
    }
    
    private fun getOriginalText(messageId: String): String? {
        return try {
            settings.getString(PREF_PREFIX + messageId, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve original: ${e.message}", e)
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
        
        hookSendMessage()
        installOutgoingRendering()
    }

    private fun hookSendMessage() {
        try {
            val messageSendClass = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "SendE2EMessageJob"
            )
            
            val messageSendMethod = Unobfuscator.loadBlueOnReplayMessageJobMethod(classLoader)
            Log.i(TAG, "Hook installed on ${messageSendMethod.declaringClass.name}.${messageSendMethod.name}")
            
            messageSendMethod.hook {
                before {
                    Log.d(TAG, "Hook fired - checking message")
                    
                    try {
                        val obj = messageSendClass.cast(instance)
                        val rawJid = ReflectionUtils.getObjectField(obj, "jid") as? String
                        
                        if (rawJid == null) {
                            Log.w(TAG, "No JID found in SendE2EMessageJob instance")
                            return@before
                        }
                        
                        Log.d(TAG, "Raw JID: $rawJid")
                        
                        val userJid = FMessageWpp.UserJid(rawJid)
                        if (userJid.isStatus) {
                            Log.d(TAG, "Skipping status message")
                            return@before
                        }
                        
                        val normalizedChat = GoogleTranslateSettings.normalizeChatId(rawJid)
                        if (normalizedChat == null) {
                            Log.w(TAG, "Failed to normalize chat ID from $rawJid")
                            return@before
                        }
                        
                        Log.d(TAG, "Normalized chat: $normalizedChat")
                        
                        val config = configOutgoing(normalizedChat)
                        Log.d(TAG, "Config for $normalizedChat: enabled=${config.enabled}, target=${config.target}")
                        
                        if (!config.enabled) {
                            Log.d(TAG, "Outgoing translation not enabled for this chat")
                            return@before
                        }
                        
                        val fMessageField = ReflectionUtils.findFieldUsingFilter(messageSendClass) { field ->
                            FMessageWpp.TYPE.isAssignableFrom(field.type)
                        }
                        
                        if (fMessageField == null) {
                            Log.e(TAG, "FMessage field not found in SendE2EMessageJob")
                            return@before
                        }
                        
                        fMessageField.isAccessible = true
                        val fMessageObj = fMessageField.get(obj)
                        
                        if (fMessageObj == null) {
                            Log.w(TAG, "FMessage object is null")
                            return@before
                        }
                        
                        val fMessage = FMessageWpp(fMessageObj)
                        val originalText = fMessage.messageStr
                        
                        if (originalText.isNullOrBlank()) {
                            Log.d(TAG, "Message text is empty/null")
                            return@before
                        }
                        
                        if (originalText.length > 4000) {
                            Log.d(TAG, "Message too long (${originalText.length} chars), skipping")
                            return@before
                        }
                        
                        Log.i(TAG, "Translating: \"${originalText.take(50)}...\" to ${config.target}")
                        
                        val messageId = fMessage.key.messageID
                        
                        val translated = try {
                            translate(originalText, "auto", config.target).get(20, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Translation failed: ${e.message}", e)
                            main.post {
                                Utils.showToast(
                                    "Translation failed. Message not sent.",
                                    Toast.LENGTH_SHORT
                                )
                            }
                            result = null
                            return@before
                        }
                        
                        if (translated.isNullOrBlank()) {
                            Log.e(TAG, "Translation returned empty result")
                            main.post {
                                Utils.showToast(
                                    "Translation failed. Message not sent.",
                                    Toast.LENGTH_SHORT
                                )
                            }
                            result = null
                            return@before
                        }
                        
                        Log.i(TAG, "Translation result: \"${translated.take(50)}...\"")
                        
                        storeOriginalText(messageId, originalText)
                        
                        val textFields = fMessage.getObject().javaClass.declaredFields.filter { field ->
                            field.type == String::class.java
                        }
                        
                        var fieldSet = false
                        for (field in textFields) {
                            try {
                                field.isAccessible = true
                                val value = field.get(fMessage.getObject()) as? String
                                if (value == originalText) {
                                    field.set(fMessage.getObject(), translated)
                                    Log.i(TAG, "Replaced text in field ${field.name}")
                                    fieldSet = true
                                    break
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to set field ${field.name}: ${e.message}")
                                continue
                            }
                        }
                        
                        if (!fieldSet) {
                            Log.e(TAG, "No matching text field found to replace")
                        } else {
                            Log.i(TAG, "Message translated successfully for $normalizedChat")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Hook execution failed: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hook setup failed: ${e.message}", e)
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
