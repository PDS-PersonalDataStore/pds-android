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

### `listClients(): String`

Retrieve registered client applications as JSON array.
Returns JSON string containing client metadata objects.

### `revokeClient(clientId: String): Boolean`

Revoke permissions of a registered client application.
Returns `true` on success.

### `removeClient(clientId: String): Boolean`

Remove a client application registration record.
Returns `true` on success.

### `createAuthorizationIntent(...)`

Create an Intent to trigger the interactive PDS Core authorization popup dialog:
```kotlin
val intent = PdsAndroidClient.createAuthorizationIntent(
    clientName = "Receipts",
    publicKey = ed25519PublicKeyBytes,
    permissions = arrayOf("ReadEntities", "WriteEntities")
)
startActivityForResult(intent, REQUEST_CODE)
```

### `createRelation(sessionId, relationType, sourceEntityId, targetEntityId): String`

Create a binary relation between source and target entities.
Returns JSON response from PDS Core.

### `createMultimemberRelation(sessionId, relationType, membersJson): String`

Create a relation with multiple members and roles (Engine 1.5):
```kotlin
val membersJson = """
[
  {"entity_id": "$receiptId", "role": "receipt"},
  {"entity_id": "$storeId", "role": "merchant"},
  {"entity_id": "$categoryId", "role": "category"}
]
""".trimIndent()
val response = client.createMultimemberRelation(sessionId, "categorized_under", membersJson)
```

### `putEncryptedBlob(sessionId, plaintext, keyAlias, mediaType?): String`

Encrypts data with AES-256-GCM using hardware-backed Android KeyStore before transmission and stores it in PDS Core:
```kotlin
val encryptedResponse = client.putEncryptedBlob(
    sessionId = sessionId,
    plaintext = fileBytes,
    keyAlias = "user_master_key"
)
```

### `getEncryptedBlob(sessionId, blobId, keyAlias): ByteArray`

Retrieves an encrypted blob from PDS Core and decrypts it on the client device:
```kotlin
val decryptedBytes = client.getEncryptedBlob(
    sessionId = sessionId,
    blobId = blobId,
    keyAlias = "user_master_key"
)
```

### `PdsCryptoManager`

Direct cryptographic helper for client-side Zero-Knowledge encryption:
- `getOrCreateKey(keyAlias: String): SecretKey`: Generates or gets an AES-256 key in Android KeyStore.
- `encrypt(plaintext: ByteArray, keyAlias: String): ByteArray`: Returns IV prepended to AES-GCM ciphertext.
- `decrypt(encryptedPayload: ByteArray, keyAlias: String): ByteArray`: Decrypts IV + ciphertext payload.

