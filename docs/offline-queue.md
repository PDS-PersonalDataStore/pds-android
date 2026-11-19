# Offline Client Queue and Durable Cursor

PDS Android supports offline client queueing and durable cursor acknowledgment with PDS engine 1.3.

## Overview

When a client application is offline or disconnected, PDS Core records new changes in the DAG.
PDS Core stores a durable cursor in the database for each registered client.

## Operations

### 1. Read Pending Changes

Use `getPendingChanges(sessionId, limit)` to get changes that occurred while the client was disconnected:

```kotlin
val pendingJson = client.getPendingChanges(sessionId, limit = 50)
```

### 2. Acknowledge Processed Changes

After processing a change, acknowledge the change identifier:

```kotlin
client.acknowledgeChanges(sessionId, changeId)
```

This operation advances the persistent cursor of the client.

### 3. Check Current Cursor

Read the current cursor position:

```kotlin
val cursorJson = client.getClientCursor(sessionId)
```
