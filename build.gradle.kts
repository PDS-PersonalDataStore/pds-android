plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}

// Compute semantic version from Git tag or fallback
val rawTag: String = System.getenv("GITHUB_REF_NAME")?.removePrefix("v") ?: ""
val pdsVersion: String = if (rawTag.isNotEmpty()) {
    rawTag
} else {
    try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        val out = process.inputStream.bufferedReader().readText().trim().removePrefix("v")
        if (out.isNotEmpty()) out else "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}

extra["pdsVersion"] = pdsVersion
extra["pdsVersionCode"] = try {
    val hyphenIndex = pdsVersion.indexOf('-')
    val (versionCore, pre) = if (hyphenIndex != -1) {
        val core = pdsVersion.substring(0, hyphenIndex)
        val suffix = pdsVersion.substring(hyphenIndex + 1)
        val preNumber = Regex("""\d+""").findAll(suffix).lastOrNull()?.value?.toIntOrNull() ?: 0
        core to preNumber.coerceIn(0, 98)
    } else {
        pdsVersion to 99
    }
    val parts = versionCore.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val code = major * 1000000 + minor * 10000 + patch * 100 + pre
    if (code > 0) code else 1
} catch (_: Exception) {
    1
}

