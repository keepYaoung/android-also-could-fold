package dev.tommy.foldshell
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as FoldApplication
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("code")?.toString()?.trim() ?: return
        app.pair(app.pairingPort, code)
    }
}
