package com.wmods.wppenhacer.xposed.features.others

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

/** All view/cache state is confined to the main thread. Never modifies stored messages. */
internal class GoogleTranslateChatUi(
    private val loader: ClassLoader,
    private val translate: (String?, String) -> CompletableFuture<String?>
) {
    private val main = Handler(Looper.getMainLooper())
    private data class RequestKey(val chat: String, val id: String, val text: String, val language: String)
    private data class Rendered(val original: CharSequence, val rendered: String)
    private val renderedViews = WeakHashMap<TextView, Rendered>()
    private val bound = WeakHashMap<ViewGroup, RequestKey>()
    private val cache = object : LinkedHashMap<RequestKey, String>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, String>?) = size > 200
    }
    private val pending = HashMap<RequestKey, CompletableFuture<String?>>()
    private val failures = object : LinkedHashMap<RequestKey, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, Long>?) = size > 200
    }
    private val settings get() = WppCore.getPrivPrefs()
    private fun key(chat: String?) = "google_translation_v2_" + (chat ?: "default")
    private fun language(chat: String): String = settings.getString(key(chat), null)
        ?: settings.getString(key(null), "off") ?: "off"
    private fun chatId(jid: FMessageWpp.UserJid?): String? =
        (jid?.phoneRawString ?: jid?.userRawString)?.takeIf {
            it.endsWith("@s.whatsapp.net") || it.endsWith("@lid") || it.endsWith("@g.us")
        }

    fun install() {
        MenuHome.addMenuItem { menu, activity ->
            if (menu.findItem(R.string.google_translate) == null) {
                menu.add(0, R.string.google_translate, 0, "Google Translate")
                    .setOnMenuItemClickListener { showSettings(activity, null); true }
            }
        }
        // Failure of one optional menu hook must not disable the message renderer.
        try {
            XposedBridge.hookMethod(Unobfuscator.loadOnCreatedMenuConversation(loader), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val menu = param.args.firstOrNull() as? Menu ?: return
                    if (menu.findItem(R.string.google_translate) != null) return
                    menu.add(0, R.string.google_translate, 0, "Google Translate")
                        .setOnMenuItemClickListener {
                            val activity = WppCore.getCurrentActivity()
                            val chat = chatId(WppCore.getCurrentUserJid())
                            if (activity != null && chat != null) showSettings(activity, chat)
                            true
                        }
                }
            })
        } catch (e: Exception) { XposedBridge.log("Google Translate: chat settings hook unavailable (${e.javaClass.simpleName})") }
        ConversationItemListener.conversationListeners.add(object : ConversationItemListener.OnConversationItemListener() {
            override fun onItemBind(fMessage: FMessageWpp, view: ViewGroup, position: Int, convertView: View?) {
                bind(fMessage, view)
            }
        })
        installManualAction()
    }

    private fun showSettings(activity: Activity, chat: String?) {
        val current = if (chat == null) settings.getString(key(null), "off") else settings.getString(key(chat), null)
        val label = when (current) {
            null -> "Use global default"
            "off" -> "Off"
            else -> GoogleTranslateLanguages.entries.firstOrNull { it.first == current }?.second ?: current
        }
        val choices = if (chat == null) arrayOf<CharSequence?>("Automatic translation: $label — choose language", "Turn off")
            else arrayOf<CharSequence?>("Automatic translation: $label — choose language", "Turn off for this chat", "Use global default", "Global settings")
        AlertDialogWpp(activity).setTitle(if (chat == null) "Google Translate · Global" else "Google Translate · This chat")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showLanguages(activity) { save(chat, it) }
                    1 -> save(chat, "off")
                    2 -> save(chat, null)
                    3 -> showSettings(activity, null)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun save(chat: String?, value: String?) {
        settings.edit().apply { if (value == null) remove(key(chat)) else putString(key(chat), value) }.apply()
        failures.clear()
        ConversationItemListener.notifyDataSetChanged()
    }

    private fun showLanguages(activity: Activity, selected: (String) -> Unit) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(activity).apply { hint = "Search languages"; isSingleLine = true }
        val list = ListView(activity)
        val all = GoogleTranslateLanguages.entries.sortedBy { it.second.lowercase(Locale.ROOT) }
        var visible = all
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, visible.map { "${it.second} (${it.first})" }.toMutableList())
        list.adapter = adapter
        layout.addView(TextView(activity).apply {
            text = "Incoming text is sent to Google when displayed. Originals stay visible."
            setPadding(16, 12, 16, 12)
        })
        layout.addView(search)
        layout.addView(list, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * .55).toInt()))
        val dialog = AlertDialogWpp(activity).setTitle("Translate into")
            .setView(layout).setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, position, _ -> selected(visible[position].first); dialog.dismiss() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                visible = all.filter { it.first.contains(query, true) || it.second.contains(query, true) }
                adapter.clear(); adapter.addAll(visible.map { "${it.second} (${it.first})" })
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        dialog.show()
    }

    private fun bind(message: FMessageWpp, root: ViewGroup) {
        bound.remove(root)
        val textView = root.findViewById<TextView>(Utils.getID("message_text", "id")) ?: return
        // Restore only our own output; WhatsApp may already have rebound this recycled view.
        renderedViews.remove(textView)?.let { if (textView.text.toString() == it.rendered) textView.text = it.original }
        if (message.key.isFromMe) return
        val chat = chatId(message.key.remoteJid) ?: return
        val target = language(chat)
        if (target == "off") return
        val text = message.messageStr?.takeIf { it.isNotBlank() && it.length <= 4000 } ?: return
        val request = RequestKey(chat, message.key.messageID, text, target)
        bound[root] = request
        cache[request]?.let { render(textView, it); return }
        if ((failures[request] ?: 0L) > SystemClock.elapsedRealtime()) return
        val future = request(request)
        val weakRoot = WeakReference(root)
        future.whenComplete { result, error -> main.post {
            val view = weakRoot.get()
            if (error == null && result != null && view != null && bound[view] == request &&
                language(chat) == target && ConversationItemListener.isViewBoundToMessage(view, request.id)) {
                view.findViewById<TextView>(Utils.getID("message_text", "id"))?.let { render(it, result) }
            }
        } }
    }

    private fun render(view: TextView, translation: String) {
        val original = renderedViews[view]?.original ?: android.text.SpannedString(view.text)
        if (original.toString().trim() == translation.trim()) return
        val output = SpannableStringBuilder(original).append("\n\nGoogle Translate\n").append(translation)
        renderedViews[view] = Rendered(original, output.toString())
        view.text = output
    }

    private fun request(request: RequestKey): CompletableFuture<String?> {
        cache[request]?.let { return CompletableFuture.completedFuture(it) }
        pending[request]?.let { return it }
        if (pending.size >= 32) return CompletableFuture<String?>().apply {
            completeExceptionally(IllegalStateException("Translation queue full"))
        }
        val future = translate(request.text, request.language)
        pending[request] = future
        future.whenComplete { result, error -> main.post {
            pending.remove(request)
            if (error == null && result != null) cache[request] = result
            else failures[request] = SystemClock.elapsedRealtime() + 60_000
        } }
        return future
    }

    private fun installManualAction() {
        try {
            XposedBridge.hookAllConstructors(Unobfuscator.loadPopupWindowMessageClass(loader), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = WppCore.getCurrentActivity() ?: return
                    val popup = param.thisObject as? PopupWindow ?: return
                    val root = popup.contentView as? ViewGroup ?: return
                    val raw = param.args.firstOrNull { FMessageWpp.TYPE.isInstance(it) } ?: return
                    val message = FMessageWpp(raw)
                    val text = message.messageStr?.takeIf { it.isNotBlank() } ?: return
                    val chat = chatId(message.key.remoteJid) ?: return
                    val tray = root.findViewById<LinearLayout>(Utils.getID("reactions_tray_layout", "id")) ?: return
                    if (tray.findViewWithTag<View>("wa_google_translate") != null) return
                    // Same wrapping pattern as CopySelectionMessage; preserve existing actions.
                    val children = (0 until tray.childCount).map { tray.getChildAt(it) }
                    tray.removeAllViews()
                    val row = LinearLayout(activity).apply { orientation = tray.orientation }
                    children.forEach { row.addView(it) }
                    tray.orientation = LinearLayout.VERTICAL
                    tray.addView(row)
                    tray.addView(Button(activity).apply {
                        tag = "wa_google_translate"
                        this.text = "Translate with Google"
                        setOnClickListener {
                            popup.dismiss()
                            val target = language(chat)
                            if (target == "off") showLanguages(activity) { manual(activity, chat, message.key.messageID, text, it) }
                            else manual(activity, chat, message.key.messageID, text, target)
                        }
                    })
                }
            })
        } catch (e: Exception) { XposedBridge.log("Google Translate: manual action hook unavailable (${e.javaClass.simpleName})") }
    }

    private fun manual(activity: Activity, chat: String, id: String, text: String, language: String) {
        if (text.length > 4000) {
            AlertDialogWpp(activity).setTitle("Google Translate").setMessage("This message is too long to translate.").setPositiveButton("Close", null).show()
            return
        }
        val output = TextView(activity).apply { this.text = "Translating…"; setPadding(32, 24, 32, 24); setTextIsSelectable(true) }
        val scroll = ScrollView(activity).apply { addView(output) }
        val dialog = AlertDialogWpp(activity).setTitle("Google Translate").setView(scroll).setPositiveButton("Close", null).create()
        dialog.show()
        request(RequestKey(chat, id, text, language)).whenComplete { result, error -> main.post {
            if (!activity.isDestroyed && dialog.isShowing) {
                output.text = if (error == null && result != null) result else "Translation unavailable. Check your connection or try another language."
            }
        } }
    }
}
