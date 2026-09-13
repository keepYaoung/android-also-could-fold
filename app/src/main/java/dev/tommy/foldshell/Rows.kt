package dev.tommy.foldshell

import android.view.View
import android.widget.TextView

/** Binds the shared list row: leading icon tile, title, optional subtitle, trailing status. */
fun View.bindRow(icon: String, title: String, subtitle: String?, trailing: String?, trailingColor: Int, onClick: (() -> Unit)?) {
    findViewById<TextView>(R.id.row_icon).text = icon
    findViewById<TextView>(R.id.row_title).text = title
    findViewById<TextView>(R.id.row_subtitle).apply { text = subtitle; visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE }
    findViewById<TextView>(R.id.row_trailing).apply { text = trailing; setTextColor(trailingColor); visibility = if (trailing.isNullOrEmpty()) View.GONE else View.VISIBLE }
    if (onClick != null) setOnClickListener { onClick() } else { setOnClickListener(null); isClickable = false }
}
