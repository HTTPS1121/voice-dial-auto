package com.example.voicedialauto.playreg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlin.system.exitProcess

/**
 * Runs inside a process forked by Shizuku (shell UID). Exposes a tiny command runner
 * for the experimental `cmd package set-installer` path.
 */
class ShellUserService : Service() {

    private val stub = object : IShellUserService.Stub() {
        override fun runCommand(command: String): String {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val out = process.inputStream.bufferedReader().readText().trim()
                val err = process.errorStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                buildString {
                    append("exit=$code")
                    if (out.isNotBlank()) append("\n").append(out)
                    if (err.isNotBlank()) append("\n").append(err)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "runCommand failed: $command", t)
                "error: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        override fun destroy() {
            try { exitProcess(0) } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent): IBinder = stub

    companion object { private const val TAG = "ShellUserService" }
}
