package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
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
    
    private companion object {
        const val TAG = "[WaeOutTranslate]"
        const val PREF_PREFIX = "gtrans_out_orig_"
        const val MAX_STORED_ORIGINALS = 200
        const val TRANSLATION_TIMEOUT_SECONDS = 5L
        const val MENU_ITEM_ID = 0x7fff0001 // Unique ID for our menu item
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
        
        hookConversationMenu()
        installOutgoingRendering()
    }

    private fun hookConversationMenu() {
        YukiLog.log("$TAG Hooking conversation menu for translate button")
        
        val onCreateMenuConversationMethod = Unobfuscator.loadOnCreatedMenuConversation(classLoader)
        
        onCreateMenuConversationMethod.hook {
            after {
                try {
                    val menu = args[0] as Menu
                    
                    // Check if menu item already exists
                    if (menu.findItem(MENU_ITEM_ID) != null) {
                        return@after
                    }
                    
                    YukiLog.log("$TAG Adding translate menu item to conversation toolbar")
                    
                    val menuItem = menu.add(0, MENU_ITEM_ID, 0, "Translate")
                    menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    
                    // Try to use a translate icon, fall back to text
                    try {
                        val iconId = Utils.getID("ic_translate", "drawable")
                        if (iconId != 0) {
                            menuItem.setIcon(iconId)
                        } else {
                            menuItem.setTitle("T")
                        }
                    } catch (e: Exception) {
                        menuItem.setTitle("T")
                        YukiLog.log("$TAG Using text 'T' instead of icon")
                    }
                    
                    menuItem.setOnMenuItemClickListener {
                        YukiLog.log("$TAG Translate menu item clicked")
                        handleTranslateClick()
                        true
                    }
                    
                    YukiLog.log("$TAG Translate menu item added successfully")
                    
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Failed to add menu item: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleTranslateClick() {
        if (!isTranslating.compareAndSet(false, true)) {
            YukiLog.log("$TAG Already translating, ignoring click")
            return
        }
        
        try {
            // Get current activity
            val activity = WppCore.getCurrentActivity()
            if (activity == null) {
                YukiLog.log("$TAG ERROR: No current activity")
                isTranslating.set(false)
                Utils.showToast("Error: No active conversation", Toast.LENGTH_SHORT)
                return
            }
            
            // Find entry EditText at click time
            val entryEditText = activity.findViewById<EditText>(Utils.getID("entry", "id"))
            if (entryEditText == null) {
                YukiLog.log("$TAG ERROR: Entry EditText not found")
                isTranslating.set(false)
                Utils.showToast("Error: Could not find message input", Toast.LENGTH_SHORT)
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
            
            // Get target language
            val currentJid = WppCore.getCurrentUserJid()
            val chat = currentJid?.let { chatId(it.userJid) }
            val config = configOutgoing(chat)
            
            val targetLang = config.target
            if (targetLang.isNullOrBlank()) {
                YukiLog.log("$TAG No target language configured")
                isTranslating.set(false)
                Utils.showToast("Please set a target language in translation settings", Toast.LENGTH_SHORT)
                return
            }
            
            YukiLog.log("$TAG Starting translation: \"${originalText.take(50)}...\" -> $targetLang")
            
            // Show progress
            Utils.showToast("Translating...", Toast.LENGTH_SHORT)
            
            // Translate on background thread
            scope.launch {
                try {
                    val startTime = System.currentTimeMillis()
                    val translated = translate(originalText, "auto", targetLang)
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
                            isTranslating.set(false)
                        }
                    }
                    
                } catch (e: Exception) {
                    YukiLog.log("$TAG ERROR: Translation failed: ${e.javaClass.simpleName}: ${e.message}")
                    
                    main.post {
                        try {
                            Utils.showToast("Translation failed: ${e.message}", Toast.LENGTH_SHORT)
                        } catch (ex: Exception) {
                            YukiLog.log("$TAG ERROR in failure handler: ${ex.message}")
                        } finally {
                            isTranslating.set(false)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            YukiLog.log("$TAG ERROR in handleTranslateClick: ${e.message}")
            e.printStackTrace()
            Utils.showToast("Translation error", Toast.LENGTH_SHORT)
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
