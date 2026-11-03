package com.wmods.wppenhacer.xposed.features.others.translation

/**
 * Result of a translation request, including which provider was actually used
 */
data class TranslationResult(
    val text: String?,
    val providerName: String,
    val providerBadge: String,
    private val providerId: String? = null
) {
    /**
     * Get the provider ID for cache key
     * For fallback, always returns "google"
     */
    fun getProviderId(): String = providerId ?: when {
        providerBadge == "G" -> "google"
        providerBadge == "AI" -> "openai"
        else -> "google"
    }
    
    companion object {
        fun google(text: String?) = TranslationResult(text, "Google Translate", "G", "google")
        fun ai(text: String?, model: String?) = TranslationResult(
            text, 
            if (model != null) "AI ($model)" else "AI",
            "AI",
            "openai"
        )
        fun fallback(text: String?) = TranslationResult(text, "Google (AI failed)", "G", "google")
    }
}
