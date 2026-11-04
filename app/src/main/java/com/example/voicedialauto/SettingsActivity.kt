package com.example.voicedialauto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.example.voicedialauto.contacts.ContactMatcher
import com.example.voicedialauto.contacts.ContactsCache
import com.example.voicedialauto.contacts.MatchAttemptLog
import com.example.voicedialauto.playreg.InstallSourceStatus
import com.example.voicedialauto.playreg.PlayRegistration
import com.example.voicedialauto.util.Permissions
import com.example.voicedialauto.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Material 3 settings with a root list of headers; each opens its own preference sub-screen.
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        toolbar = findViewById(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
            else finish()
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, RootFragment())
                .commit()
        }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        updateTitle()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment!!,
        )
        fragment.arguments = pref.extras
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .addToBackStack(pref.key)
            .commit()
        toolbar.title = pref.title
        return true
    }

    private fun updateTitle() {
        toolbar.title = if (supportFragmentManager.backStackEntryCount == 0) {
            getString(R.string.ph_settings)
        } else {
            toolbar.title
        }
    }

    // ------------------------------------------------------------------ root

    class RootFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "voice_dial_prefs"
            setPreferencesFromResource(R.xml.preferences, rootKey)
            refreshSummaries()
        }

        override fun onResume() {
            super.onResume()
            refreshSummaries()
        }

        private fun refreshSummaries() {
            val prefs = Prefs(requireContext())
            findPreference<Preference>("hdr_speech")?.summary =
                getString(R.string.st_hdr_speech_sum) + " · " +
                    (if (prefs.primaryLanguage == Prefs.LANG_HE) getString(R.string.ph_lang_badge_he)
                    else getString(R.string.ph_lang_badge_en))
            findPreference<Preference>("hdr_timing")?.summary =
                getString(
                    R.string.st_sync_done,
                    (prefs.nameWindowMs / 1000).toInt(),
                    "${prefs.choiceWindowMs / 1000} שנ׳ רשימה",
                ).let { getString(R.string.st_hdr_timing_sum) }
            findPreference<Preference>("hdr_contacts")?.summary =
                getString(R.string.st_contacts_count_sum, ContactsCache.count)
            val aa = InstallSourceStatus.read(requireContext())
            findPreference<Preference>("hdr_aa")?.summary =
                if (aa.accepted) getString(R.string.st_aa_status_ok, aa.initiating ?: "—")
                else getString(R.string.st_hdr_aa_sum)
        }
    }

    // ------------------------------------------------------------------ shared helpers

    abstract class BasePrefsFragment : PreferenceFragmentCompat() {
        protected val io = Executors.newSingleThreadExecutor()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "voice_dial_prefs"
            onBuildPreferences(rootKey)
        }

        protected abstract fun onBuildPreferences(rootKey: String?)

        protected fun bindSeek(key: String, format: (Int) -> String) {
            val p = findPreference<SeekBarPreference>(key) ?: return
            p.summary = format(p.value)
            p.setOnPreferenceChangeListener { pref, v -> pref.summary = format(v as Int); true }
        }

        protected fun formatHalf(half: Int): String =
            if (half % 2 == 0) (half / 2).toString()
            else String.format(Locale.US, "%.1f", half / 2.0)

        protected fun formatTenths(tenths: Int): String =
            if (tenths % 10 == 0) (tenths / 10).toString()
            else String.format(Locale.US, "%.1f", tenths / 10.0)

        protected fun formatSyncAge(elapsedAt: Long): String {
            if (elapsedAt == 0L) return getString(R.string.st_sync_never)
            val ago = SystemClock.elapsedRealtime() - elapsedAt
            if (ago < 60_000L) return getString(R.string.st_sync_just_now)
            return getString(R.string.st_sync_minutes_ago, (ago / 60_000L).toInt().coerceAtLeast(1))
        }
    }

    class SpeechFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_speech, rootKey)
            bindSeek(Prefs.KEY_VAD_HANGOVER_TENTHS) {
                getString(R.string.st_vad_hangover_sum, formatTenths(it))
            }
            // v13: which VAD engine actually runs (Silero, or WebRTC if Silero failed to load).
            findPreference<Preference>("vad_info")?.let { pref ->
                val s = Prefs(requireContext()).vadSensitivity
                // v14: the engine the LAST session actually used, with the reason.
                val st = com.example.voicedialauto.speech.VadStatus
                val last = if (st.atMs == 0L) getString(R.string.st_vad_last_none) else {
                    val name = when (st.engine) { "Silero" -> "Silero VAD"; "WebRTC" -> "WebRTC VAD"; else -> getString(R.string.st_vad_rms) }
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(st.atMs))
                    getString(R.string.st_vad_last, name, st.reason, time)
                }
                pref.summary = last + "\n" + getString(R.string.st_vad_info_sum, s.start.toString(), s.end.toString())
            }
        }
    }

    class TimingFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_timing, rootKey)
            bindSeek(Prefs.KEY_NAME_WINDOW_S) { getString(R.string.st_name_window_sum, it.toString()) }
            bindSeek(Prefs.KEY_LIST_WINDOW_S) { getString(R.string.st_list_window_sum, it.toString()) }
            findPreference<Preference>("timing_reset")?.setOnPreferenceClickListener {
                findPreference<SeekBarPreference>(Prefs.KEY_NAME_WINDOW_S)?.value = Prefs.NAME_WINDOW_DEFAULT_S
                findPreference<SeekBarPreference>(Prefs.KEY_LIST_WINDOW_S)?.value = Prefs.LIST_WINDOW_DEFAULT_S
                onBuildPreferences(null)
                true
            }
        }
    }

    class DialFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_dial, rootKey)
            bindSeek(Prefs.KEY_COUNTDOWN_HALF_S) { half ->
                if (half == 0) getString(R.string.st_countdown_zero)
                else getString(R.string.st_countdown_sum, formatHalf(half))
            }
        }
    }

    class ContactsFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_contacts, rootKey)
            refresh()
            findPreference<Preference>("sync_contacts")?.setOnPreferenceClickListener {
                ContactsCache.refresh(requireContext()) { list ->
                    if (!isAdded) return@refresh
                    val msg = getString(R.string.st_sync_done, list.size, formatSyncAge(ContactsCache.lastSyncElapsedMs))
                    it.summary = msg
                    refresh()
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
                true
            }
            findPreference<Preference>("matcher_test")?.setOnPreferenceClickListener {
                showMatcherDialog(); true
            }
        }

        override fun onResume() {
            super.onResume()
            refresh()
        }

        private fun refresh() {
            findPreference<Preference>("contacts_count")?.summary =
                getString(R.string.st_contacts_count_sum, ContactsCache.count)
            val age = ContactsCache.lastSyncElapsedMs
            findPreference<Preference>("sync_contacts")?.summary =
                if (age == 0L) getString(R.string.st_sync_never)
                else getString(R.string.st_sync_done, ContactsCache.count, formatSyncAge(age))
        }

        private fun showMatcherDialog() {
            val view = layoutInflater.inflate(R.layout.dialog_matcher, null)
            val input = view.findViewById<TextInputEditText>(R.id.matcherInput)
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.st_matcher)
                .setView(view)
                .setPositiveButton(R.string.st_search) { _, _ -> runMatch(input.text?.toString().orEmpty()) }
                .setNegativeButton(R.string.st_close, null)
                .create()
            input.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) {
                    dialog.dismiss(); runMatch(input.text?.toString().orEmpty()); true
                } else false
            }
            dialog.show()
        }

        private fun runMatch(text: String) {
            if (text.isBlank() || Permissions.missing(requireContext()).isNotEmpty()) return
            ContactsCache.withContacts(requireContext()) { contacts ->
                if (!isAdded) return@withContacts
                val msg = when (val r = ContactMatcher().match(listOf(text), contacts)) {
                    is ContactMatcher.Result.Single -> getString(
                        R.string.st_result_single,
                        "${r.match.contact.displayName} · ${r.match.contact.number}",
                    )
                    is ContactMatcher.Result.Multiple -> getString(
                        R.string.st_result_multi,
                        r.matches.mapIndexed { i, m ->
                            "${i + 1}. ${m.contact.displayName} · ${m.contact.number}"
                        }.joinToString("\n"),
                    )
                    ContactMatcher.Result.None -> getString(R.string.st_result_none)
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("\"$text\"")
                    .setMessage(msg)
                    .setPositiveButton(R.string.st_close, null)
                    .show()
            }
        }
    }

    class AaFragment : BasePrefsFragment() {
        private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) refreshAa()
        }

        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_aa, rootKey)
            findPreference<Preference>("aa_status")?.setOnPreferenceClickListener { refreshAa(); true }
            findPreference<Preference>("aa_reinstall")?.setOnPreferenceClickListener { onReinstall(); true }
            findPreference<Preference>("aa_adb")?.setOnPreferenceClickListener { onCopyAdb(); true }
            findPreference<Preference>("aa_set_installer")?.setOnPreferenceClickListener { onSetInstaller(); true }
            refreshAa()
        }

        override fun onStart() {
            super.onStart()
            runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
            refreshAa()
        }

        override fun onStop() {
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
            super.onStop()
        }

        private fun refreshAa() {
            val pref = findPreference<Preference>("aa_status") ?: return
            val status = InstallSourceStatus.read(requireContext())
            val none = getString(R.string.st_none)
            if (status.accepted) {
                pref.summary = getString(R.string.st_aa_status_ok, status.initiating ?: none)
                pref.setIcon(android.R.drawable.presence_online)
            } else {
                pref.summary = getString(
                    R.string.st_aa_status_bad,
                    status.installing ?: none,
                    status.initiating ?: none,
                    status.originating ?: none,
                )
                pref.setIcon(android.R.drawable.presence_busy)
            }
            pref.title = getString(R.string.st_aa_status) + if (status.accepted) "  ✓" else "  ✗"
        }

        private fun onReinstall() {
            when {
                !PlayRegistration.isShizukuRunning() -> showShizukuMissing()
                !PlayRegistration.hasShizukuPermission() -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.st_aa_shizuku_need_perm)
                        .setMessage(R.string.st_aa_shizuku_need_perm_body)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            PlayRegistration.requestShizukuPermission()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                else -> {
                    val progress = MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.st_aa_installing).setCancelable(false).show()
                    io.execute {
                        val err = PlayRegistration.reinstallAsPlay(requireContext().applicationContext)
                        requireActivity().runOnUiThread {
                            progress.dismiss()
                            when (err) {
                                null -> MaterialAlertDialogBuilder(requireContext())
                                    .setMessage(R.string.st_aa_install_launched)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> refreshAa() }
                                    .show()
                                "APK_COPY_FAILED" ->
                                    Toast.makeText(requireContext(), R.string.st_aa_copy_failed, Toast.LENGTH_LONG).show()
                                else ->
                                    Toast.makeText(requireContext(), R.string.st_aa_install_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        private fun showShizukuMissing() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.st_aa_shizuku_missing_title)
                .setMessage(R.string.st_aa_shizuku_missing_body)
                .setPositiveButton(R.string.st_aa_shizuku_download) { _, _ ->
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next/releases")),
                    )
                }
                .setNeutralButton(R.string.st_aa_shizuku_open) { _, _ ->
                    val launch = requireContext().packageManager
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch != null) startActivity(launch)
                    else startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rushiranpise/Shizuku-Next/releases")),
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun onCopyAdb() {
            val cmd = PlayRegistration.adbCommand(requireContext())
            requireContext().getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("adb", cmd))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.st_aa_adb)
                .setMessage(cmd + "\n\n" + getString(R.string.st_aa_adb_sum))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            Toast.makeText(requireContext(), R.string.st_aa_adb_copied, Toast.LENGTH_SHORT).show()
        }

        private fun onSetInstaller() {
            when {
                !PlayRegistration.isShizukuRunning() -> showShizukuMissing()
                !PlayRegistration.hasShizukuPermission() -> PlayRegistration.requestShizukuPermission()
                else -> {
                    val progress = MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.st_aa_installing).setCancelable(false).show()
                    io.execute {
                        val result = PlayRegistration.setInstallerViaCmd(requireContext().applicationContext)
                        requireActivity().runOnUiThread {
                            progress.dismiss()
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.st_aa_cmd_result)
                                .setMessage(result)
                                .setPositiveButton(android.R.string.ok) { _, _ -> refreshAa() }
                                .show()
                        }
                    }
                }
            }
        }
    }

    class DiagFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_diag, rootKey)
            findPreference<Preference>("match_log")?.setOnPreferenceClickListener {
                showMatchLog(); true
            }
            // v12: persistent file log.
            findPreference<Preference>("log_view")?.setOnPreferenceClickListener {
                val text = com.example.voicedialauto.util.FileLog.tail(60_000).ifBlank { getString(R.string.st_log_empty) }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.st_log_view)
                    .setMessage(text.takeLast(60_000))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            findPreference<Preference>("log_copy")?.setOnPreferenceClickListener {
                val text = com.example.voicedialauto.util.FileLog.tail(200_000)
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("voicedial.log", text))
                android.widget.Toast.makeText(requireContext(), getString(R.string.st_log_copied, text.length / 1024), android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("log_share")?.setOnPreferenceClickListener { shareLog(); true }
            findPreference<Preference>("silero_selftest")?.setOnPreferenceClickListener { sileroSelfTest(); true }
            findPreference<Preference>("log_clear")?.setOnPreferenceClickListener {
                com.example.voicedialauto.util.FileLog.clear()
                MatchAttemptLog.clear(); com.example.voicedialauto.speech.RecognizerSessionLog.clear()
                android.widget.Toast.makeText(requireContext(), R.string.st_log_cleared, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }

        /** v14: proves Silero loads and runs on THIS device (fresh instance, generated audio). */
        private fun sileroSelfTest() {
            val ctx = requireContext().applicationContext
            val dlg = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.st_silero_test)
                .setMessage(R.string.st_silero_running)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            Thread({
                val text = runCatching {
                    val r = com.example.voicedialauto.speech.vad.VadSelfTest.run(ctx)
                    com.example.voicedialauto.util.FileLog.log("vad", "self-test: $r")
                    r.toString()
                }.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" }
                activity?.runOnUiThread { dlg.setMessage(text) }
            }, "silero-selftest").start()
        }

        private fun shareLog() {
            val ctx = requireContext()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val out = java.io.File(java.io.File(ctx.cacheDir, "logshare").apply { mkdirs(); listFiles()?.forEach { it.delete() } },
                "voicedial-$stamp.txt")
            com.example.voicedialauto.util.FileLog.exportTo(out)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.apkfiles", out)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, out.name)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(send, getString(R.string.st_log_share)))
        }

        private fun showMatchLog() {
            val items = MatchAttemptLog.snapshot()
            val sessions = com.example.voicedialauto.speech.RecognizerSessionLog.snapshot()
            if (items.isEmpty() && sessions.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.st_match_log)
                    .setMessage(R.string.st_match_log_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
            // v10: per-session mic log (diagnoses listen/stop loops on the list screen).
            val sessionText = if (sessions.isEmpty()) "" else
                "MIC SESSIONS (newest first)\n" + sessions.take(15).joinToString("\n") { e ->
                    "${if (e.continuous) "cont" else "name"}${if (e.reused) "·reuse" else ""}${if (e.carMic) "·carmic" else ""} " +
                        "${e.durationMs}ms ${e.end}${if (e.speech) " speech=\"${e.partial}\"" else " no-speech"}" +
                        (if (e.events.isNotBlank()) "\n   ${e.events}" else "")
                } + "\n\n"
            val perf = "contacts load ${ContactsCache.lastLoadMs}ms · index build " +
                "${com.example.voicedialauto.contacts.ContactIndex.lastBuildMs}ms · ${ContactsCache.count} contacts\n\n"
            val text = perf + sessionText + items.joinToString("\n\n") { a ->
                val top = a.top.joinToString { pair ->
                    "${pair.first} (${"%.2f".format(Locale.US, pair.second)})"
                }
                "[${a.source.ifBlank { "?" }}] ${a.verdict} · contacts=${a.contactsCount}\n" +
                    "final: ${a.hypotheses.joinToString(" | ").ifBlank { "—" }}\n" +
                    "partial: ${a.lastPartial ?: "—"}\n" +
                    "query: ${a.extracted.joinToString(" | ").ifBlank { "—" }}\n" +
                    "top: $top\n" +
                    "decision: ${a.decision.ifBlank { "—" }}" +
                    (a.timeline?.let { "\ntiming(ms): ${it.format()}" } ?: "")
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.st_match_log)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.st_match_log_clear) { _, _ ->
                    MatchAttemptLog.clear(); com.example.voicedialauto.speech.RecognizerSessionLog.clear()
                }
                .show()
        }
    }

    class PermFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_perm, rootKey)
            findPreference<Preference>("permissions")?.setOnPreferenceClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().packageName, null),
                    ),
                )
                true
            }
        }

        override fun onResume() {
            super.onResume()
            val missing = Permissions.missing(requireContext())
            findPreference<Preference>("permissions")?.summary =
                if (missing.isEmpty()) getString(R.string.st_perm_ok)
                else getString(
                    R.string.ph_perm_missing,
                    missing.joinToString(", ") { it.substringAfterLast('.') },
                )
        }
    }

    class AboutFragment : BasePrefsFragment() {
        override fun onBuildPreferences(rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_about, rootKey)
            findPreference<Preference>("version")?.summary = runCatching {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrNull()
        }
    }
}
