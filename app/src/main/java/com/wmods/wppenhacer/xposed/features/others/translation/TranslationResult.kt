package com.wmods.wppenhacer.xposed.features.others.translation

/**
 * Result of a translation request, including which provider was actually used
 */
data class TranslationResult(
    val text: String?,
    val providerName: String,
    val providerBadge: String
) {
    companion object {
        fun google(text: String?) = TranslationResult(text, "Google Translate", "G")
        fun ai(text: String?, model: String?) = TranslationResult(
            text, 
            if (model != null) "AI ($model)" else "AI",
            "AI"
        )
        fun fallback(text: String?) = TranslationResult(text, "Google (AI failed)", "G")
    }
}
