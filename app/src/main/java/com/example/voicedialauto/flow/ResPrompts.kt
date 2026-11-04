package com.example.voicedialauto.flow

import android.content.Context
import com.example.voicedialauto.R

/** v12: the spoken prompts, from resources, used by BOTH the phone and the car (Hebrew in values-iw). */
class ResPrompts(private val context: Context) : Prompts {
    override val languageTag: String
        get() = context.resources.configuration.locales[0].toLanguageTag()
    override fun singleMatch(name: String) = context.getString(R.string.tts_single_match, name)
    override fun picked(name: String) = context.getString(R.string.tts_calling, name)
    override fun multiple(count: Int) = context.getString(R.string.tts_multiple)
    override fun noMatch(): String = context.getString(R.string.tts_no_match)
    override fun sayNumber(): String = context.getString(R.string.tts_say_number)
}
