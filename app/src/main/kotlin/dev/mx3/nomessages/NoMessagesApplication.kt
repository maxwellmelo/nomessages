package dev.mx3.nomessages

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.mx3.nomessages.runtime.NoMessagesController

/** Session teardown and a later unlock always share the same process-wide owner. */
class NoMessagesApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val controller: NoMessagesController by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[NoMessagesController::class.java]
    }
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) {
            ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) controller.lock()
                }
            }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
