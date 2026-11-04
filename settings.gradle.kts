pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // v13: only the android-vad WebRTC module comes from JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.gkonovalov.android-vad") } }
    }
}
rootProject.name = "VoiceDialAuto"
include(":app")
