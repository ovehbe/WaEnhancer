package com.wmods.wppenhacer.xposed.features.others

/** Pure settings model: opening/editing dialogs never mutates persisted settings. */
internal data class GoogleTranslationConfig(
    val enabled: Boolean = false,
    val source: String = "auto",
    val target: String = "en"
) {
    fun encode() = "${if (enabled) "on" else "off"}|$source|$target"
}

internal object GoogleTranslateSettings {
    private val languages = GoogleTranslateLanguages.entries.map { it.first }.toSet()

    fun decode(value: String?): GoogleTranslationConfig? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 3 || parts[0] !in setOf("on", "off") ||
            (parts[1] != "auto" && parts[1] !in languages) || parts[2] !in languages
        ) return null
        return GoogleTranslationConfig(parts[0] == "on", parts[1], parts[2])
    }

    fun legacy(value: String?): GoogleTranslationConfig? = when {
        value == "off" -> GoogleTranslationConfig()
        value in languages -> GoogleTranslationConfig(true, "auto", value!!)
        else -> null
    }

    fun resolve(local: String?, global: String?, legacyLocal: String?, legacyGlobal: String?) =
        decode(local) ?: legacy(legacyLocal) ?: decode(global) ?: legacy(legacyGlobal)
        ?: GoogleTranslationConfig()

    /** Preserve full group IDs (including hyphens); discard status/channel/broadcast IDs. */
    fun normalizeChatId(raw: String?): String? {
        val jid = raw?.replaceFirst("\\.[\\d:]+@".toRegex(), "@") ?: return null
        val name = jid.substringBefore('@', "")
        if (name.isEmpty()) return null
        return jid.takeIf { it.substringAfter('@', "") in setOf("g.us", "s.whatsapp.net", "lid") }
    }
    
    /**
     * Resolve translation config for a message using the same logic as the UI.
     * Returns normalized chat ID and config (per-chat, then global, then legacy).
     */
    fun resolveForMessage(
        fmessage: com.wmods.wppenhacer.xposed.core.components.FMessageWpp,
        prefs: android.content.SharedPreferences
    ): Pair<String?, GoogleTranslationConfig> {
        val chatId = try {
            val remoteJid = fmessage.key.remoteJid
            val conversationJid = com.wmods.wppenhacer.xposed.utils.ReflectionUtils.getObjectField(
                fmessage.key.thisObject,
                "conversationRowJid"
            )
            val jidToUse = conversationJid ?: remoteJid
            val rawString = com.wmods.wppenhacer.xposed.utils.ReflectionUtils.callMethod(
                jidToUse,
                "getRawString"
            ) as? String
            val normalized = normalizeChatId(rawString)
            
            if (normalized?.endsWith("@g.us") == true) {
                normalized
            } else {
                val phoneJid = com.wmods.wppenhacer.xposed.core.WppCore.getPhoneJidFromUserJid(jidToUse)
                val phoneRaw = phoneJid?.let {
                    com.wmods.wppenhacer.xposed.utils.ReflectionUtils.callMethod(
                        it,
                        "getRawString"
                    ) as? String
                }
                normalizeChatId(phoneRaw) ?: normalized
            }
        } catch (e: Exception) {
            null
        }
        
        val config = if (chatId != null) {
            val key = "google_translation_v3_$chatId"
            val legacyKey = "google_translation_v2_$chatId"
            resolve(
                prefs.getString(key, null),
                prefs.getString("google_translation_v3_default", null),
                prefs.getString(legacyKey, null),
                prefs.getString("google_translation_v2_default", null)
            )
        } else {
            GoogleTranslationConfig()
        }
        
        return Pair(chatId, config)
    }
}
