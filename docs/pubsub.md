# Real-Time Change Notifications (Pub/Sub)

PDS Android supports real-time change notifications with Android Binder IPC.

## Overview

Clients can subscribe to change events instead of polling the storage engine.
When any client inserts or deletes records, `PdsService` broadcasts an event.

## Subscription Granularity

Clients can filter events by entity type:

1. Global: Set `entityType = "*"` to receive all changes.
2. Collection: Set `entityType = "notes"` to receive changes for one entity type.

## Event Format

The service delivers a JSON array string to the listener:

```json
[
  {
    "action": "put",
    "entity_type": "notes",
    "id": "01BX5ZZKBKACTAV9WEVGEMMVRZ",
    "timestamp_ms": 1700000000000
  }
]
```

## Kotlin Usage

```kotlin
// Subscribe to note changes
client.subscribe("notes") { changeEventsJson ->
    // Handle change event
}

// Unsubscribe when done
client.unsubscribe(listener)
```
