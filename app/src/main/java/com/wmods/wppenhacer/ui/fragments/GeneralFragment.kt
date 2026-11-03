package com.wmods.wppenhacer.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.preference.EditableListPreference
import com.wmods.wppenhacer.preference.PasswordEditTextPreference
import com.wmods.wppenhacer.ui.fragments.base.BaseFragment
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment
import com.wmods.wppenhacer.xposed.features.others.translation.OpenAITranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeneralFragment : BaseFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.frag_container, GeneralPreferenceFragment())
                .commitNow()
        }

        val activityIntent = activity?.intent
        val scrollToKey = activityIntent?.getStringExtra("scroll_to_preference")
        if (scrollToKey != null) {
            root?.postDelayed({
                (childFragmentManager.findFragmentById(R.id.frag_container) as? BasePreferenceFragment)
                    ?.scrollToPreference(scrollToKey)
            }, 300)
            activityIntent.removeExtra("scroll_to_preference")
        }
        return root
    }

    class GeneralPreferenceFragment : BasePreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            setPreferencesFromResource(R.xml.fragment_general, rootKey)
        }

        override fun onResume() {
            super.onResume()
            setDisplayHomeAsUpEnabled(false)
        }
    }

    class HomeGeneralPreference : BasePreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            setPreferencesFromResource(R.xml.preference_general_home, rootKey)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    class HomeScreenGeneralPreference : BasePreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            setPreferencesFromResource(R.xml.preference_general_homescreen, rootKey)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    class ConversationGeneralPreference : BasePreferenceFragment() {
        
        private lateinit var providerPref: ListPreference
        private lateinit var baseUrlPref: EditTextPreference
        private lateinit var apiKeyPref: PasswordEditTextPreference
        private lateinit var modelPref: EditableListPreference
        private lateinit var fallbackPref: SwitchPreference
        private lateinit var fetchModelsPref: Preference
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            setPreferencesFromResource(R.xml.preference_general_conversation, rootKey)
            setDisplayHomeAsUpEnabled(true)
            
            setupTranslationPreferences()
        }
        
        private fun setupTranslationPreferences() {
            providerPref = findPreference("translation_provider") ?: return
            baseUrlPref = findPreference("translation_ai_base_url") ?: return
            apiKeyPref = findPreference("translation_ai_api_key") ?: return
            modelPref = findPreference("translation_ai_model") ?: return
            fallbackPref = findPreference("translation_ai_fallback_to_google") ?: return
            fetchModelsPref = findPreference("translation_fetch_models") ?: return
            
            // Update visibility based on provider
            updateAiFieldsVisibility()
            
            // Listen to provider changes
            providerPref.setOnPreferenceChangeListener { _, newValue ->
                // Update visibility after change
                providerPref.value = newValue.toString()
                updateAiFieldsVisibility()
                true
            }
            
            // Handle "Fetch Models" button
            fetchModelsPref.setOnPreferenceClickListener {
                fetchModelsFromApi()
                true
            }
            
            // Set model summary provider
            modelPref.summaryProvider = Preference.SummaryProvider<EditableListPreference> { pref ->
                pref.value ?: "gpt-4o-mini"
            }
        }
        
        private fun updateAiFieldsVisibility() {
            val isAiSelected = providerPref.value == "openai"
            
            baseUrlPref.isVisible = isAiSelected
            apiKeyPref.isVisible = isAiSelected
            modelPref.isVisible = isAiSelected
            fallbackPref.isVisible = isAiSelected
            fetchModelsPref.isVisible = isAiSelected
        }
        
        private fun fetchModelsFromApi() {
            val context = requireContext()
            val prefs = preferenceManager.sharedPreferences ?: return
            
            // Show loading toast
            Toast.makeText(context, "Fetching models...", Toast.LENGTH_SHORT).show()
            
            // Create provider and fetch models off main thread
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val provider = OpenAITranslationProvider(prefs)
                    
                    withContext(Dispatchers.IO) {
                        provider.fetchModels { result ->
                            launch(Dispatchers.Main) {
                                result.fold(
                                    onSuccess = { models ->
                                        if (models.isEmpty()) {
                                            Toast.makeText(
                                                context,
                                                "No models found",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            modelPref.setEntriesAndValues(models)
                                            Toast.makeText(
                                                context,
                                                "Found ${models.size} models",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(
                                            context,
                                            "Error: ${error.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
