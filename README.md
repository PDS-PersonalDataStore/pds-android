# PDS Android Client SDK

[![CI Build & Tests](https://github.com/Tau2C/pds-android/actions/workflows/ci.yml/badge.svg)](https://github.com/Tau2C/pds-android/actions/workflows/ci.yml)

Oficjalna biblioteka kliencka Android SDK (`top.tau2c.pds.client`) dla systemu Personal Data Store (PDS). Umożliwia aplikacjom zewnętrznym komunikację z usługą systemową PDS Core (`top.tau2c.pds.core`) przez Android IPC (AIDL / Binder).

Aplikacja PDS Core (usługa systemowa, silnik Rust E2EE) znajduje się w dedykowanym repozytorium:
[PDS-PersonalDataStore/pds-android-app](https://github.com/PDS-PersonalDataStore/pds-android-app).

---

## Architektura integracji

```text
+-----------------------+     +-----------------------+
|  Aplikacja Notatek    |     |  Aplikacja To-Do      |
|  (Klient PDS SDK)     |     |  (Klient PDS SDK)     |
+-----------+-----------+     +-----------+-----------+
            |                             |
            |   Android IPC (AIDL/Binder) |
            +--------------+--------------+
                           |
                           v
        +-------------------------------------+
        |       Aplikacja: PDS Core           |
        |      `top.tau2c.pds.core`           |
        |                                     |
        |      PdsService (Bound Service)     |
        +-------------------------------------+
```

---

## Instalacja SDK

Dodaj bibliotekę do pliku `build.gradle.kts`:

```kotlin
dependencies {
    implementation("top.tau2c.pds:client-sdk:0.9.0")
}
```

---

## Sposób użycia w aplikacji klienckiej

1. Dodaj deklaracje w `AndroidManifest.xml`:

```xml
<uses-permission android:name="top.tau2c.pds.permission.ACCESS_PDS" />

<queries>
    <package android:name="top.tau2c.pds.core" />
</queries>
```

2. Połącz się z usługą w kodzie Kotlin:

```kotlin
val pdsClient = PdsAndroidClient(context)
val connected = pdsClient.connect()
if (connected) {
    // Połączono z PDS Core
}
```

---

## Budowanie biblioteki

Projekt wymaga środowiska Java JDK 17 i systemu Gradle (brak zależności od NDK / Rust):

```bash
gradle check :client-sdk:assembleRelease
```

---

## Dokumentacja

- [Przewodnik integracji](docs/integration-guide.md)
- [Architektura systemu](docs/architecture.md)
- [Referencja API klienta](docs/api-reference.md)
- [Powiadomienia w czasie rzeczywistym (Pub/Sub)](docs/pubsub.md)
- [Kolejka offline i trwały kursor](docs/offline-queue.md)

