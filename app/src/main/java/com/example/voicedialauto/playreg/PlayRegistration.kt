package com.example.voicedialauto.playreg

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Re-registers this app as Play-sourced so Android Auto will show it.
 *
 * The reliable path (KingInstaller's "Shizuku Trick"):
 *  1. Copy `base.apk` (+ splits, if any) into cache.
 *  2. Build `ACTION_INSTALL_PACKAGE` with EXTRA_INSTALLER_PACKAGE_NAME=com.android.vending
 *     and EXTRA_NOT_UNKNOWN_SOURCE=true, URI from our FileProvider.
 *  3. Launch that Intent **as shell** via Shizuku-wrapped
 *     `IActivityTaskManager.startActivityAsUser(…, callingPackage="com.android.shell", …)`,
 *     so Package Installer keeps the extras (a normal startActivity from our UID would not).
 *
 * Same signing key → Package Installer treats it as an update; no uninstall needed.
 */
object PlayRegistration {

    const val VENDING = InstallSourceStatus.VENDING
    private const val TAG = "PlayRegistration"
    private const val CACHE_DIR = "selfapk"

    fun isShizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try { Shizuku.requestPermission(requestCode) } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** Content URI used by the adb fallback (served by [SelfApkProvider]). */
    fun adbContentUri(context: Context): Uri =
        Uri.parse("content://${context.packageName}.apk/base.apk")

    /** Ready-to-paste adb command that launches Package Installer as shell with Play extras. */
    fun adbCommand(context: Context): String {
        val uri = adbContentUri(context)
        return "adb shell am start " +
            "-a android.intent.action.INSTALL_PACKAGE " +
            "-d $uri " +
            "-t application/vnd.android.package-archive " +
            "--es android.intent.extra.INSTALLER_PACKAGE_NAME $VENDING " +
            "--ez android.intent.extra.NOT_UNKNOWN_SOURCE true"
    }

    /**
     * Copies this app's APK(s) into cache and launches Package Installer as shell.
     * Returns null on success, or a Hebrew-friendly error message.
     * Must NOT be called on the main thread (copies can be tens of MB).
     */
    fun reinstallAsPlay(context: Context): String? {
        if (!isShizukuRunning()) return "SHIZUKU_NOT_RUNNING"
        if (!hasShizukuPermission()) return "SHIZUKU_NO_PERMISSION"
        val apk = prepareApkCopy(context) ?: return "APK_COPY_FAILED"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkfiles", apk)
        listOf(
            "com.android.shell",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
        ).forEach { pkg ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra("android.intent.extra.REFERRER_NAME", "android-app://$VENDING")
            // PackageManager.INSTALL_REASON_USER = 4 on recent APIs; KingInstaller uses 1.
            putExtra("android.content.pm.extra.INSTALL_REASON", 1)
        }
        return if (startInstallActivityAsShell(intent)) null else "SHELL_LAUNCH_FAILED"
    }

    /**
     * Experimental: try `cmd package set-installer` via a Shizuku-spawned process.
     * Returns the command output (or an error tag). Many devices reject this without root.
     */
    fun setInstallerViaCmd(context: Context): String {
        if (!isShizukuRunning()) return "SHIZUKU_NOT_RUNNING"
        if (!hasShizukuPermission()) return "SHIZUKU_NO_PERMISSION"
        val pkg = context.packageName
        val commands = listOf(
            "cmd package set-installer --user 0 $pkg $VENDING",
            "cmd package set-installer $pkg $VENDING",
            "pm set-installer $pkg $VENDING",
        )
        return withUserService(context) { svc ->
            val out = StringBuilder()
            for (cmd in commands) {
                val result = try { svc.runCommand(cmd) } catch (t: Throwable) { "error: ${t.message}" }
                out.appendLine("$cmd → $result")
                if (result.startsWith("exit=0")) return@withUserService out.toString().trim()
            }
            out.toString().trim().ifBlank { "CMD_FAILED" }
        }
    }

    /**
     * Binds a short-lived Shizuku UserService (shell UID), runs [block], then destroys it.
     * Blocks the calling thread (call from a background executor).
     */
    private fun withUserService(context: Context, block: (IShellUserService) -> String): String {
        // ComponentName package must match the hosting app; class is our ShellUserService.
        val args = Shizuku.UserServiceArgs(
            android.content.ComponentName(context.packageName, ShellUserService::class.java.name)
        ).daemon(false).processNameSuffix("shell").debuggable(true).version(1)
        val lock = java.util.concurrent.CountDownLatch(1)
        var service: IShellUserService? = null
        var error: String? = null
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    service = IShellUserService.Stub.asInterface(binder)
                } else error = "USER_SERVICE_DEAD"
                lock.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        }
        return try {
            Shizuku.bindUserService(args, conn)
            if (!lock.await(8, java.util.concurrent.TimeUnit.SECONDS)) return "USER_SERVICE_TIMEOUT"
            val svc = service ?: return error ?: "USER_SERVICE_NULL"
            try { block(svc) } finally {
                runCatching { svc.destroy() }
                runCatching { Shizuku.unbindUserService(args, conn, true) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "withUserService failed", t)
            "USER_SERVICE_ERROR: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------ internals

    /** Prefer a single base.apk; if the install is split, build a combined APKs zip for Package Installer. */
    private fun prepareApkCopy(context: Context): File? {
        val dir = File(context.cacheDir, CACHE_DIR).also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        val ai = context.applicationInfo
        val sourceDir = File(ai.sourceDir)
        if (!sourceDir.isFile) return null
        val splitNames = ai.splitSourceDirs
        return if (splitNames.isNullOrEmpty()) {
            val dest = File(dir, "base.apk")
            sourceDir.copyTo(dest, overwrite = true)
            dest
        } else {
            // Package Installer accepts a .apks zip with base + splits named base.apk / split_*.apk.
            val dest = File(dir, "app.apks")
            java.util.zip.ZipOutputStream(FileOutputStream(dest)).use { zos ->
                fun put(name: String, file: File) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                put("base.apk", sourceDir)
                splitNames.forEachIndexed { i, path ->
                    val f = File(path)
                    put(f.name.ifBlank { "split_$i.apk" }, f)
                }
            }
            dest
        }
    }

    /**
     * KingInstaller ShizukuUtils.startInstallActivityAsShell: wrap the activity_task binder,
     * obtain IActivityTaskManager, reflectively call startActivityAsUser with callingPackage
     * "com.android.shell" and userId 0.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi")
    private fun startInstallActivityAsShell(intent: Intent): Boolean {
        return try {
            val binder = SystemServiceHelper.getSystemService("activity_task")
                ?: SystemServiceHelper.getSystemService("activity")
                ?: return false
            val wrapped = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java).also { it.isAccessible = true }
            val atm = asInterface.invoke(null, wrapped) ?: return false
            val method = atm.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" }
                ?: atm.javaClass.declaredMethods.firstOrNull { it.name == "startActivityAsUser" }
                ?: throw NoSuchMethodException("startActivityAsUser")
            method.isAccessible = true
            val args = arrayOfNulls<Any>(method.parameterTypes.size)
            for (i in method.parameterTypes.indices) {
                val t = method.parameterTypes[i]
                args[i] = when {
                    t.isAssignableFrom(Intent::class.java) -> intent
                    // callingPackage / callingFeatureId slots early in the signature.
                    t == String::class.java && (i == 1 || i == 2) -> "com.android.shell"
                    t == Int::class.javaPrimitiveType -> 0 // userId / requestCode / flags
                    else -> null
                }
            }
            method.invoke(atm, *args)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startInstallActivityAsShell failed", t)
            false
        }
    }

}
