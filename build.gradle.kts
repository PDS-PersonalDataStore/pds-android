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
        if (out.isNotEmpty()) out else "0.2.1"
    } catch (_: Exception) {
        "0.2.1"
    }
}

extra["pdsVersion"] = pdsVersion
extra["pdsVersionCode"] = try {
    val clean = pdsVersion.split("-")[0]
    val parts = clean.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    major * 10000 + minor * 100 + patch
} catch (_: Exception) {
    1
}

