plugins {
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


