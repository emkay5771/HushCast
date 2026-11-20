package app.podiumpodcasts.podium.background.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the phone is connected to a car, the same way the Car App Library's `CarConnection`
 * finds out: Android Auto answers a query on `content://androidx.car.app.connection` and broadcasts
 * `…CAR_CONNECTION_UPDATED` when that changes. 0 = not connected, 1 = native, 2 = projection.
 *
 * Media3's controller events can't be used for this: a car browsing the library never gets
 * onPostConnect or onDisconnected, and its command connection "disconnects" after 5 idle minutes.
 */
class CarConnectionWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Main thread; called on every change, starting with the first answer. */
    private val onChange: (connected: Boolean) -> Unit
) {
    private var connected: Boolean? = null
    private var registered = false

    private val isAutomotive by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
        }
        refresh()
    }

    fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refresh() {
        scope.launch {
            val state = if (isAutomotive) 1 else withContext(Dispatchers.IO) { queryState() }
            val now = state != 0
            if (now != connected) {
                connected = now
                Log.i(TAG, "Car connection: $state")
                onChange(now)
            }
        }
    }

    private fun queryState(): Int = runCatching {
        context.contentResolver.query(URI.toUri(), arrayOf(COLUMN), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(COLUMN)
            if (idx >= 0 && c.moveToNext()) c.getInt(idx) else 0
        } ?: 0
    }.getOrDefault(0)

    companion object {
        private const val TAG = "HushCastCar"
        private const val URI = "content://androidx.car.app.connection"
        private const val COLUMN = "CarConnectionState"
        private const val ACTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    }
}
