package dev.tommy.foldshell

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** App-level language override. English by default, regardless of the system locale. */
object Lang {
    val tags = listOf("en", "ko", "ja")
    val names = listOf("English", "한국어", "日本語")
    fun current(context: Context): String =
        context.getSharedPreferences("fold", Context.MODE_PRIVATE).getString("lang", null)?.takeIf { it in tags } ?: "en"
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current(base))
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
    fun label(context: Context) = names[tags.indexOf(current(context))]
    fun pick(activity: Activity) {
        val index = tags.indexOf(current(activity))
        AlertDialog.Builder(activity).setTitle(R.string.language)
            .setSingleChoiceItems(names.toTypedArray(), index) { dialog, which ->
                activity.getSharedPreferences("fold", Context.MODE_PRIVATE).edit().putString("lang", tags[which]).apply()
                dialog.dismiss()
                (activity.application as FoldApplication).refreshNotifications()
                activity.recreate()
            }.setNegativeButton(R.string.close, null).show()
    }
}
