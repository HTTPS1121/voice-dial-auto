
package com.example.voicedialauto.playreg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceStatusTest {

    @Test fun playViaPackageInstallerIsAccepted() {
        assertTrue(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.android.packageinstaller",
        ))
        assertTrue(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.google.android.packageinstaller",
        ))
        assertTrue(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.android.vending",
        ))
    }

    @Test fun oemPackageInstallerAccepted() {
        assertTrue(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.samsung.android.packageinstaller",
        ))
        assertTrue(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.foo.bar.packageinstaller",
        ))
    }

    @Test fun shellInitiatorRejectedEvenWithVendingInstaller() {
        // adb install -i com.android.vending → installing=vending, initiating=shell → AA hides it.
        assertFalse(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = "com.android.shell",
        ))
    }

    @Test fun normalSideloadRejected() {
        assertFalse(InstallSourceStatus.isAccepted(
            installing = "com.android.shell",
            initiating = "com.android.shell",
        ))
        assertFalse(InstallSourceStatus.isAccepted(
            installing = null,
            initiating = null,
        ))
        assertFalse(InstallSourceStatus.isAccepted(
            installing = InstallSourceStatus.VENDING,
            initiating = null,
        ))
    }

    @Test fun adbCommandMentionsPlayExtras() {
        // Pure string shape check (no Context): the command template must carry both extras.
        val sample = "adb shell am start " +
            "-a android.intent.action.INSTALL_PACKAGE " +
            "-d content://com.example.voicedialauto.apk/base.apk " +
            "-t application/vnd.android.package-archive " +
            "--es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending " +
            "--ez android.intent.extra.NOT_UNKNOWN_SOURCE true"
        assertTrue(sample.contains("INSTALLER_PACKAGE_NAME com.android.vending"))
        assertTrue(sample.contains("NOT_UNKNOWN_SOURCE true"))
        assertTrue(sample.contains("content://") && sample.contains("/base.apk"))
    }
}
