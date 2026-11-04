package com.example.voicedialauto.playreg

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Snapshot of [PackageManager.getInstallSourceInfo] for our own package.
 * Android Auto (Car App Library) only shows the app when [installingPackageName] is Play
 * (`com.android.vending`) and the initiating package is a package installer or Play itself.
 * Pure-ish: the classification lives here so the Settings UI and unit tests share one rule.
 */
data class InstallSourceStatus(
    val initiating: String?,
    val installing: String?,
    val originating: String?,
    /** True when AA should accept this install as Play-sourced. */
    val accepted: Boolean,
) {
    companion object {
        const val VENDING = "com.android.vending"
        private val ACCEPTABLE_INITIATORS = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",
            // Some OEMs wrap the system installer under their own package.
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.google.android.apps.nbu.files", // Files-by-Google "Open with" sometimes
        )

        fun read(context: Context): InstallSourceStatus {
            val pm = context.packageManager
            val pkg = context.packageName
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
                from(info)
            } else {
                @Suppress("DEPRECATION")
                val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
                InstallSourceStatus(
                    initiating = installer,
                    installing = installer,
                    originating = null,
                    accepted = installer == VENDING,
                )
            }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        fun from(info: InstallSourceInfo?): InstallSourceStatus {
            if (info == null) return InstallSourceStatus(null, null, null, false)
            val initiating = info.initiatingPackageName
            val installing = info.installingPackageName
            val originating = info.originatingPackageName
            return InstallSourceStatus(initiating, installing, originating, isAccepted(installing, initiating))
        }

        /** Classification rule used by the UI and by unit tests. */
        fun isAccepted(installing: String?, initiating: String?): Boolean {
            if (installing != VENDING) return false
            if (initiating.isNullOrBlank()) return false
            if (initiating in ACCEPTABLE_INITIATORS) return true
            // Any package whose name ends with ".packageinstaller" (OEM variants).
            return initiating.endsWith(".packageinstaller")
        }
    }
}
