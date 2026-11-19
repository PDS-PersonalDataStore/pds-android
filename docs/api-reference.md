# API Reference

This document describes the API of `top.tau2c.pds.client.PdsAndroidClient`.

## Constructor

```kotlin
val client = PdsAndroidClient(context: Context)
```

Create a new instance with the Android application context.

## Methods

### `connect(): Boolean`

Connect to the PDS Core service through Android Binder.
Returns `true` when the connection succeeds.

### `disconnect()`

Disconnect from the PDS Core service and release the Binder connection.

### `isApproved(): Boolean`

Check if PDS Core approved this client package.
Returns `true` when approved.

### `put(entityType: String, dataJson: String): String?`

Store a new record in PDS storage.
Returns the new record identifier, or null on error.

### `get(entityType: String, id: String): String?`

Retrieve a record by identifier.
Returns JSON string of the record, or null if not found.

### `query(entityType: String, filterJson: String = "{}"): String?`

Search records by entity type and optional filter.
Returns JSON array string of records.

### `delete(entityType: String, id: String): Boolean`

Remove a record by identifier.
Returns `true` on success.

### `putBlob(data: ByteArray): String?`

Store raw binary data in content-addressed storage.
Returns the SHA-256 hash of the blob.

### `getBlob(hash: String): ByteArray?`

Retrieve binary data by its content hash.
Returns the byte array, or null if not found.
