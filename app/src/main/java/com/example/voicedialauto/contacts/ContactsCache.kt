package com.example.voicedialauto.contacts

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide contacts snapshot.
 *
 * Car-only "transcript OK → no match" (v6): Android Auto often starts a fresh process via
 * CarAppService without MainActivity. The cache was empty; session-start refresh raced with
 * listenName refresh; [withContacts] treated `emptyList()` as ready, so matching ran against
 * 0 contacts and the matcher returned None while the transcript was still shown.
 *
 * v7: Application context; non-empty snapshot served immediately; empty never "ready";
 * empty results never wipe a good list; generation-tagged loads; [lastSyncElapsedMs] for UI.
 *
 * v11 (slow list in the car): every listening start called refresh(), i.e. a full
 * ContactsContract reload (phones + aliases) running on the phone while the driver spoke and
 * while we matched; a refresh during a load also bumped the generation, so the in-flight load was
 * thrown away and restarted (double load on a cold car process). Now:
 *  - [ensureFresh] reloads only if the cache is empty, older than [MAX_AGE_MS], or a
 *    ContentObserver saw a contacts change;
 *  - refresh() while a load is running just waits for it (no restart);
 *  - the match index ([ContactIndex]) is built on the loader thread right after each load.
 */
object ContactsCache {
    private const val TAG = "ContactsCache"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    @Volatile var snapshot: List<ContactEntry>? = null
        private set
    /** elapsedRealtime of the last finished load, or 0 if never. */
    @Volatile var lastSyncElapsedMs: Long = 0L
        private set
    @Volatile private var loading = false
    private val waiters = ArrayList<(List<ContactEntry>) -> Unit>()
    private var pendingApp: Context? = null

    /** Duration of the last ContactsContract load + index build (ms), for the debug log. */
    @Volatile var lastLoadMs: Long = 0L
        private set
    /** First names shared by multiple contacts — holds fast endpointing until a surname is heard. */
    @Volatile var ambiguousFirstNames: Set<String> = emptySet()
        private set
    @Volatile private var dirty = false
    @Volatile private var observing = false
    const val MAX_AGE_MS = 10 * 60_000L

    val count: Int get() = snapshot?.size ?: 0

    /** Reload only when needed (empty / stale / changed). Cheap to call on every listening start. */
    fun ensureFresh(context: Context) {
        val app = context.applicationContext
        observe(app)
        val s = snapshot
        val age = SystemClock.elapsedRealtime() - lastSyncElapsedMs
        if (s.isNullOrEmpty() || dirty || lastSyncElapsedMs == 0L || age > MAX_AGE_MS) refresh(app)
    }

    private fun observe(app: Context) {
        if (observing) return
        observing = true
        runCatching {
            app.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) { dirty = true }
                },
            )
        }.onFailure { observing = false; Log.w(TAG, "observer", it) }
    }
    val isLoading: Boolean get() = loading

    fun refresh(context: Context, onLoaded: ((List<ContactEntry>) -> Unit)? = null) {
        val app = context.applicationContext
        observe(app)
        var startNow = false
        var gen = 0
        synchronized(waiters) {
            if (onLoaded != null) waiters += onLoaded
            pendingApp = app
            if (loading) {
                // v11: piggyback on the running load instead of discarding and restarting it.
            } else {
                loading = true
                gen = generation.incrementAndGet()
                startNow = true
            }
        }
        if (startNow) startLoad(app, gen)
    }

    /**
     * Prefer a non-empty cache. Never treat an empty list as final — reload instead.
     * Waits when a load is already in flight.
     */
    fun withContacts(context: Context, block: (List<ContactEntry>) -> Unit) {
        val hit = synchronized(waiters) {
            val s = snapshot
            if (s != null && s.isNotEmpty()) s else null
        }
        if (hit != null) {
            block(hit)
            return
        }
        refresh(context, block)
    }

    private fun startLoad(app: Context, gen: Int) {
        io.execute {
            dirty = false
            val t0 = SystemClock.elapsedRealtime()
            val list = runCatching { ContactsRepository(app).loadDialableContacts() }
                .onFailure { Log.e(TAG, "load failed", it) }
                .getOrDefault(emptyList())
            val tLoad = SystemClock.elapsedRealtime()
            // Prewarm the matcher index here, off the main thread (cached by list identity).
            if (list.isNotEmpty()) runCatching { ContactIndex.of(list) }
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "load gen=$gen size=${list.size} query=${tLoad - t0}ms index=${ContactIndex.lastBuildMs}ms")
            main.post { onLoadFinished(app, gen, list) }
        }
    }

    private fun onLoadFinished(app: Context, gen: Int, list: List<ContactEntry>) {
        var restartApp: Context? = null
        var restartGen = 0
        var pending: List<(List<ContactEntry>) -> Unit> = emptyList()
        synchronized(waiters) {
            if (gen != generation.get()) {
                restartApp = pendingApp ?: app
                restartGen = generation.get()
                loading = true
            } else {
                if (list.isNotEmpty() || snapshot.isNullOrEmpty()) {
                    snapshot = list
                    ambiguousFirstNames = NameCompletenessGate.ambiguousFirstNames(list)
                }
                lastSyncElapsedMs = SystemClock.elapsedRealtime()
                loading = false
                pending = waiters.toList()
                waiters.clear()
            }
        }
        if (restartApp != null) {
            startLoad(restartApp, restartGen)
            return
        }
        val ready = snapshot.orEmpty()
        Log.i(TAG, "serving ${pending.size} waiter(s) with ${ready.size} contacts")
        pending.forEach { it(ready) }
    }

    internal fun resetForTests() {
        synchronized(waiters) {
            snapshot = null
            ambiguousFirstNames = emptySet()
            loading = false
            generation.set(0)
            waiters.clear()
            pendingApp = null
            lastSyncElapsedMs = 0L
        }
    }

    internal fun seedForTests(list: List<ContactEntry>) {
        synchronized(waiters) {
            snapshot = list
            ambiguousFirstNames = NameCompletenessGate.ambiguousFirstNames(list)
            loading = false
            lastSyncElapsedMs = SystemClock.elapsedRealtime()
        }
    }
}
