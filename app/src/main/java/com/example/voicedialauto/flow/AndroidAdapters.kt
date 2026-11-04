package com.example.voicedialauto.flow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.voicedialauto.contacts.ContactEntry
import com.example.voicedialauto.contacts.ContactsCache

class MainThreadScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun now(): Long = SystemClock.elapsedRealtime()
    override fun post(delayMs: Long, block: () -> Unit): Cancellable {
        val r = Runnable { block() }
        handler.postDelayed(r, delayMs)
        return object : Cancellable { override fun cancel() = handler.removeCallbacks(r) }
    }
}

/** Always uses applicationContext so CarContext quirks cannot affect the ContentResolver. */
class CachedContactsSource(context: Context) : ContactsSource {
    private val app = context.applicationContext
    override fun refresh(onLoaded: ((List<ContactEntry>) -> Unit)?) = ContactsCache.refresh(app, onLoaded)
    override fun withContacts(block: (List<ContactEntry>) -> Unit) = ContactsCache.withContacts(app, block)
    override fun ensureFresh() = ContactsCache.ensureFresh(app)
}

/** Single background thread for matching; results posted to the main thread. */
object BackgroundWorker : Worker {
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vd-match") }
    private val main = Handler(Looper.getMainLooper())
    override fun <T> submit(work: () -> T, onDone: (T) -> Unit) {
        exec.execute {
            val r = runCatching(work)
            main.post { onDone(r.getOrThrow()) }
        }
    }
}
