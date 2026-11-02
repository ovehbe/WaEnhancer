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
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GoogleTranslateOutgoing(
    loader: ClassLoader,
    preferences: SharedPreferences,
    private val translate: (String?, String, String) -> CompletableFuture<String?>
) : Feature(loader, preferences) {

    private val main = Handler(Looper.getMainLooper())
    private val settings get() = WppCore.getPrivPrefs()
    
    private val originalTexts = ConcurrentHashMap<String, String>()
    private val renderedViews = WeakHashMap<TextView, String>()
    
    private fun keyOutgoing(chat: String?) = "google_translation_outgoing_v1_" + (chat ?: "default")
    
    private fun configOutgoing(chat: String?): GoogleTranslationConfigOutgoing {
        val local = chat?.let { settings.getString(keyOutgoing(it), null) }
        val global = settings.getString(keyOutgoing(null), null)
        return GoogleTranslateSettingsOutgoing.decode(local) 
            ?: GoogleTranslateSettingsOutgoing.decode(global) 
            ?: GoogleTranslationConfigOutgoing()
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
            
            messageSendMethod.hook {
                before {
                    try {
                        val obj = messageSendClass.cast(instance)
                        val rawJid = ReflectionUtils.getObjectField(obj, "jid") as? String ?: return@before
                        
                        val userJid = FMessageWpp.UserJid(rawJid)
                        if (userJid.isStatus) return@before
                        
                        val chat = GoogleTranslateSettings.normalizeChatId(rawJid) ?: return@before
                        
                        val config = configOutgoing(chat)
                        if (!config.enabled) return@before
                        
                        val fMessageField = ReflectionUtils.findFieldUsingFilter(messageSendClass) { field ->
                            FMessageWpp.TYPE.isAssignableFrom(field.type)
                        } ?: return@before
                        
                        fMessageField.isAccessible = true
                        val fMessageObj = fMessageField.get(obj) ?: return@before
                        val fMessage = FMessageWpp(fMessageObj)
                        
                        val originalText = fMessage.messageStr?.takeIf { 
                            it.isNotBlank() && it.length <= 4000 
                        } ?: return@before
                        
                        val messageId = fMessage.key.messageID
                        
                        val translated = try {
                            translate(originalText, "auto", config.target).get(20, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            YukiLog.log("GoogleTranslateOutgoing: Translation failed: ${e.message}")
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
                            YukiLog.log("GoogleTranslateOutgoing: Empty translation")
                            main.post {
                                Utils.showToast(
                                    "Translation failed. Message not sent.",
                                    Toast.LENGTH_SHORT
                                )
                            }
                            result = null
                            return@before
                        }
                        
                        originalTexts[messageId] = originalText
                        
                        val textFields = fMessage.getObject().javaClass.declaredFields.filter { field ->
                            field.type == String::class.java
                        }
                        
                        for (field in textFields) {
                            try {
                                field.isAccessible = true
                                val value = field.get(fMessage.getObject()) as? String
                                if (value == originalText) {
                                    field.set(fMessage.getObject(), translated)
                                    YukiLog.log("GoogleTranslateOutgoing: Translated message for $chat")
                                    break
                                }
                            } catch (e: Exception) {
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        YukiLog.log("GoogleTranslateOutgoing: Hook execution failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            YukiLog.log("GoogleTranslateOutgoing: Hook setup failed: ${e.message}")
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
                val original = originalTexts[messageId] ?: return
                
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
