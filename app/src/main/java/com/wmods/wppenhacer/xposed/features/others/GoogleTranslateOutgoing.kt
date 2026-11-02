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
                YukiLog.log("$TAG Stored original for message $messageId")
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
            YukiLog.log("$TAG Hook installed on ${messageSendMethod.declaringClass.name}.${messageSendMethod.name}")
            
            messageSendMethod.hook {
                before {
                    YukiLog.log("$TAG Hook fired - thread=${Thread.currentThread().name}")
                    
                    try {
                        val obj = messageSendClass.cast(instance)
                        val rawJid = ReflectionUtils.getObjectField(obj, "jid") as? String
                        
                        if (rawJid == null) {
                            YukiLog.log("$TAG WARN: No JID found in SendE2EMessageJob instance")
                            return@before
                        }
                        
                        YukiLog.log("$TAG Raw JID from send job: $rawJid")
                        
                        val userJid = try {
                            FMessageWpp.UserJid(rawJid)
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR: Failed to create UserJid from $rawJid: ${e.message}")
                            return@before
                        }
                        
                        if (userJid.isStatus) {
                            YukiLog.log("$TAG Skipping status message")
                            return@before
                        }
                        
                        // Convert LID to phone JID using the same logic as the dialog
                        val normalizedChat = chatId(userJid.userJid)
                        if (normalizedChat == null) {
                            YukiLog.log("$TAG WARN: Failed to resolve chat ID from $rawJid")
                            return@before
                        }
                        
                        YukiLog.log("$TAG Resolved chat ID (for config lookup): $normalizedChat")
                        
                        val config = configOutgoing(normalizedChat)
                        YukiLog.log("$TAG Config for $normalizedChat: enabled=${config.enabled}, target=${config.target}")
                        
                        if (!config.enabled) {
                            YukiLog.log("$TAG Outgoing translation not enabled for this chat")
                            return@before
                        }
                        
                        val fMessageField = ReflectionUtils.findFieldUsingFilter(messageSendClass) { field ->
                            FMessageWpp.TYPE.isAssignableFrom(field.type)
                        }
                        
                        if (fMessageField == null) {
                            YukiLog.log("$TAG ERROR: FMessage field not found in SendE2EMessageJob")
                            return@before
                        }
                        
                        fMessageField.isAccessible = true
                        val fMessageObj = fMessageField.get(obj)
                        
                        if (fMessageObj == null) {
                            YukiLog.log("$TAG WARN: FMessage object is null")
                            return@before
                        }
                        
                        val fMessage = FMessageWpp(fMessageObj)
                        val originalText = fMessage.messageStr
                        
                        if (originalText.isNullOrBlank()) {
                            YukiLog.log("$TAG Message text is empty/null")
                            return@before
                        }
                        
                        if (originalText.length > 4000) {
                            YukiLog.log("$TAG Message too long (${originalText.length} chars), skipping")
                            return@before
                        }
                        
                        YukiLog.log("$TAG Translating: \"${originalText.take(50)}...\" to ${config.target}")
                        
                        val messageId = fMessage.key.messageID
                        
                        // NOTE: This blocks the current thread for up to 5 seconds
                        // We're hooking SendE2EMessageJob which typically runs on a background thread
                        // 5s is short enough to avoid ANR but long enough for most translations
                        val translated = try {
                            translate(originalText, "auto", config.target).get(TRANSLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
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
                            YukiLog.log("$TAG ERROR: Translation returned empty result")
                            main.post {
                                Utils.showToast(
                                    "Translation failed. Message not sent.",
                                    Toast.LENGTH_SHORT
                                )
                            }
                            result = null
                            return@before
                        }
                        
                        YukiLog.log("$TAG Translation result: \"${translated.take(50)}...\"")
                        
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
                                    YukiLog.log("$TAG Replaced text in field ${field.name}")
                                    fieldSet = true
                                    break
                                }
                            } catch (e: Exception) {
                                YukiLog.log("$TAG WARN: Failed to set field ${field.name}: ${e.message}")
                                continue
                            }
                        }
                        
                        if (!fieldSet) {
                            YukiLog.log("$TAG ERROR: No matching text field found to replace")
                        } else {
                            YukiLog.log("$TAG SUCCESS: Message translated for $normalizedChat")
                        }
                        
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
