package com.wmods.wppenhacer.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference

/**
 * ListPreference that allows manual entry if the desired value isn't in the list.
 * Stores custom entries dynamically.
 */
class EditableListPreference : ListPreference {

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(
        context,
        attrs,
        defStyleAttr,
        defStyleRes
    )

    /**
     * Update entries dynamically (e.g., after fetching from API)
     */
    fun setEntriesAndValues(items: List<String>) {
        entries = items.toTypedArray()
        entryValues = items.toTypedArray()
    }

    /**
     * Allow setting a custom value not in the list
     */
    fun setCustomValue(value: String) {
        // Add to entries if not present
        val currentEntries = entries?.toMutableList() ?: mutableListOf()
        val currentValues = entryValues?.toMutableList() ?: mutableListOf()
        
        if (!currentValues.contains(value)) {
            currentEntries.add(value)
            currentValues.add(value)
            entries = currentEntries.toTypedArray()
            entryValues = currentValues.toTypedArray()
        }
        
        this.value = value
    }
}
