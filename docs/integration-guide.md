# PDS Android Integration Guide

This guide describes how to install PDS Core and integrate the Client SDK into an Android application.

## 1. Install PDS Core Service

The service runs as a background process and manages local decentralized storage.

Download and install the APK with Android Debug Bridge (ADB):

```bash
gh release download v0.1.0 --repo Tau2C/pds-android --pattern "*.apk"
adb install app-release.apk
```

## 2. Add the Client SDK to Your Application

Download `client-sdk-release.aar`:

```bash
gh release download v0.1.0 --repo Tau2C/pds-android --pattern "*.aar"
```

Move the file to the `libs/` directory in your application module.

Add this dependency to `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/client-sdk-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

## 3. Configure Android Manifest

Add the permission and the package query to `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="top.tau2c.pds.permission.ACCESS_PDS" />

    <queries>
        <package android:name="top.tau2c.pds.core" />
        <intent>
            <action android:name="top.tau2c.pds.core.PDS_SERVICE" />
        </intent>
    </queries>

</manifest>
```

## 4. Use the Client in Kotlin

Use `PdsAndroidClient` to read and write records:

```kotlin
import top.tau2c.pds.client.PdsAndroidClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

val client = PdsAndroidClient(context)

CoroutineScope(Dispatchers.IO).launch {
    val connected = client.connect()
    if (!connected) {
        return@launch
    }

    // Save record
    val recordId = client.put("notes", """{"title": "Note 1", "body": "Content"}""")

    // Read records
    val notes = client.query("notes")

    // Disconnect
    client.disconnect()
}
```
