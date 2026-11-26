package top.tau2c.pds.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import top.tau2c.pds.core.IPdsChangeListener
import top.tau2c.pds.core.IPdsService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-level Android SDK client for integrating with the central PDS Core background service.
 */
class PdsAndroidClient(private val context: Context) {

    companion object {
        const val PDS_SERVICE_ACTION = "top.tau2c.pds.core.PDS_SERVICE"
        const val PDS_PACKAGE_NAME = "top.tau2c.pds.core"

        const val ACTION_REQUEST_AUTHORIZATION = "top.tau2c.pds.core.REQUEST_AUTHORIZATION"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_PERMISSIONS = "permissions"

        const val RESULT_CLIENT_ID = "client_id"
        const val RESULT_GRANTED_PERMISSIONS = "granted_permissions"
        const val RESULT_PRIVATE_KEY = "private_key"
        const val RESULT_PUBLIC_KEY = "public_key"

        /**
         * Creates an Intent to launch the interactive PDS Core authorization popup dialog.
         * The calling Activity should launch this via startActivityForResult or registerForActivityResult.
         * If [publicKey] is null, PDS Core will generate the client identity keys and return them in the result.
         */
        fun createAuthorizationIntent(
            clientName: String,
            publicKey: ByteArray? = null,
            permissions: Array<String> = arrayOf("ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory")
        ): Intent {
            if (publicKey != null) {
                require(publicKey.size == 32) { "Ed25519 public key must be exactly 32 bytes" }
            }
            return Intent(ACTION_REQUEST_AUTHORIZATION).apply {
                setPackage(PDS_PACKAGE_NAME)
                putExtra(EXTRA_CLIENT_NAME, clientName)
                if (publicKey != null) {
                    putExtra(EXTRA_PUBLIC_KEY, publicKey)
                }
                putExtra(EXTRA_PERMISSIONS, permissions)
            }
        }

        /**
         * Context-aware helper creating authorization intent with automatically derived identity public key.
         */
        fun createAuthorizationIntent(
            context: Context,
            clientName: String,
            permissions: Array<String> = arrayOf("ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory")
        ): Intent {
            val pubKey = PdsCryptoManager.getOrCreateIdentityPublicKey(context)
            return createAuthorizationIntent(clientName, pubKey, permissions)
        }
    }


    private var pdsService: IPdsService? = null
    private var isBound = false
    private var activeSessionId: String? = null

    /**
     * Retrieves the current active authenticated session identifier.
     */
    fun getSessionId(): String? = activeSessionId

    fun interface ChangeListener {
        fun onChanges(changeEventsJson: String)
    }

    private class ListenerEntry(
        val listener: ChangeListener,
        val filterJson: String,
        val aidlCallback: IPdsChangeListener.Stub
    )

    private val activeSubscriptions = CopyOnWriteArrayList<ListenerEntry>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            pdsService = IPdsService.Stub.asInterface(service)
            isBound = true

            // Re-register active subscriptions after reconnect
            activeSubscriptions.forEach { entry ->
                try {
                    pdsService?.registerChangeListener(entry.filterJson, entry.aidlCallback)
                } catch (_: Exception) {}
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pdsService = null
            isBound = false
        }
    }

    /**
     * Binds to the background PDS Core service.
     */
    fun connect(): Boolean {
        val intent = Intent(PDS_SERVICE_ACTION).apply {
            setPackage(PDS_PACKAGE_NAME)
        }
        isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        return isBound
    }

    /**
     * Unbinds from the PDS Core service.
     */
    fun disconnect() {
        if (isBound) {
            activeSubscriptions.forEach { entry ->
                try {
                    pdsService?.unregisterChangeListener(entry.aidlCallback)
                } catch (_: Exception) {}
            }
            context.unbindService(serviceConnection)
            isBound = false
            pdsService = null
            activeSessionId = null
        }
    }

    fun isConnected(): Boolean {
        return isBound && pdsService != null
    }

    /**
     * Sends a raw JSON request to PDS and returns the raw JSON response.
     */
    fun executeRawRequest(requestJson: String): String {
        val service = pdsService ?: throw IllegalStateException("PdsService is not connected")
        return service.executeRequest(requestJson)
    }

    /**
     * Creates an Intent to launch the interactive PDS Core authorization popup dialog using this client's identity key.
     */
    fun createAuthorizationIntent(
        clientName: String,
        permissions: Array<String> = arrayOf("ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory")
    ): Intent {
        return createAuthorizationIntent(context, clientName, permissions)
    }

    /**
     * Creates an Intent to launch the interactive PDS Core authorization popup dialog with a manually provided public key.
     * When using a manually provided key, the caller application is responsible for storing and managing its private key.
     */
    fun createManualAuthorizationIntent(
        clientName: String,
        publicKey: ByteArray,
        permissions: Array<String> = arrayOf("ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory")
    ): Intent {
        require(publicKey.size == 32) { "Ed25519 public key must be exactly 32 bytes" }
        return createAuthorizationIntent(clientName, publicKey, permissions)
    }

    /**
     * Creates an Intent to launch the interactive PDS Core authorization popup dialog allowing PDS Core
     * to generate identity keys. The generated private key will be saved automatically upon receiving the result in [handleAuthResult].
     */
    fun createAutoGeneratedAuthorizationIntent(
        clientName: String,
        permissions: Array<String> = arrayOf("ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory")
    ): Intent {
        return createAuthorizationIntent(clientName, null, permissions)
    }

    /**
     * Handles the result Intent from the authorization Activity, storing the clientId if authorization succeeded.
     * If PDS Core generated identity keys, the returned private key is also persisted in encrypted local storage.
     * Returns the authorized clientId, or null if cancelled or missing.
     */
    fun handleAuthResult(resultIntent: Intent?): String? {
        val clientId = resultIntent?.getStringExtra(RESULT_CLIENT_ID)
        val privateKey = resultIntent?.getByteArrayExtra(RESULT_PRIVATE_KEY)

        if (privateKey != null && privateKey.size == 32) {
            PdsCryptoManager.saveIdentityPrivateKey(context, privateKey)
        }

        if (!clientId.isNullOrEmpty()) {
            saveClientId(clientId)
        }
        return clientId
    }

    /**
     * Retrieves the stored registered client identifier, or null if not yet authorized.
     */
    fun getClientId(): String? {
        return PdsCryptoManager.getClientId(context)
    }

    /**
     * Persists the registered client identifier.
     */
    fun saveClientId(clientId: String) {
        PdsCryptoManager.saveClientId(context, clientId)
    }

    /**
     * Checks if this client has an authorized clientId stored locally.
     */
    fun isAuthorized(): Boolean {
        return !getClientId().isNullOrEmpty()
    }

    /**
     * Clears stored client identity and authorization details.
     */
    fun clearClientIdentity() {
        PdsCryptoManager.clearClientIdentity(context)
    }

    /**
     * Registers this client application with the provided or automatically derived public key.
     */
    fun register(clientName: String, publicKey: ByteArray? = null): String {
        val service = pdsService ?: throw IllegalStateException("PdsService is not connected")
        val key = publicKey ?: PdsCryptoManager.getOrCreateIdentityPublicKey(context)
        val id = service.registerClient(clientName, key)
        if (id.isNotEmpty()) {
            saveClientId(id)
        }
        return id
    }

    /**
     * Authenticates with PDS Core using client credentials and cryptographic challenge-response handshake.
     * Establishes and stores activeSessionId.
     */
    fun authenticate(apiVersion: String = "1.4.0"): String {
        val clientId = getClientId() ?: throw IllegalStateException("Client is not authorized. Request authorization first.")
        return authenticateWithCredentials(
            clientId = clientId,
            signer = { payload -> PdsCryptoManager.sign(context, payload) },
            apiVersion = apiVersion
        )
    }

    /**
     * Authenticates manually using an explicit clientId and a 32-byte Ed25519 private key seed managed by the application.
     */
    fun authenticateWithPrivateKey(
        clientId: String,
        privateKeySeed: ByteArray,
        apiVersion: String = "1.4.0"
    ): String {
        return authenticateWithCredentials(
            clientId = clientId,
            signer = { payload -> PdsCryptoManager.signWithPrivateKey(privateKeySeed, payload) },
            apiVersion = apiVersion
        )
    }

    /**
     * Authenticates manually with PDS Core using an explicit clientId and a custom signature function.
     * Useful when the application holds keys in its own secure hardware / HSM / key store.
     */
    fun authenticateWithCredentials(
        clientId: String,
        signer: (signingPayload: ByteArray) -> ByteArray,
        apiVersion: String = "1.4.0"
    ): String {
        val service = pdsService ?: throw IllegalStateException("PdsService is not connected")

        // 1. Generate 32-byte secure random client nonce
        val clientNonce = ByteArray(32)
        java.security.SecureRandom().nextBytes(clientNonce)

        // 2. Request challenge from PDS Core
        val challengeRespJson = service.requestChallenge(clientId, clientNonce, apiVersion)
        val challengeObj = org.json.JSONObject(challengeRespJson)
        val status = challengeObj.optString("status")
        if (status != "Challenge") {
            val err = challengeObj.optString("payload", "Unknown error requesting challenge")
            throw java.io.IOException("Authentication handshake failed at Challenge step: $err")
        }

        val challengePayload = challengeObj.getJSONObject("payload")
        val serverInstanceId = challengePayload.getString("server_instance_id")
        val negotiatedApiVersion = challengePayload.getString("api_version")
        val serverNonceArray = challengePayload.getJSONArray("server_nonce")
        val serverNonce = ByteArray(serverNonceArray.length())
        for (i in 0 until serverNonceArray.length()) {
            serverNonce[i] = serverNonceArray.getInt(i).toByte()
        }

        // 3. Compute signing payload according to PDS IPC specification
        val signingPayload = PdsCryptoManager.computeSigningPayload(
            serverInstanceId = serverInstanceId,
            clientIdUlidString = clientId,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            apiVersion = negotiatedApiVersion
        )

        // 4. Sign payload
        val signature = signer(signingPayload)

        // 5. Submit signature to PDS Core
        val authRespJson = service.authenticateClientWithSignature(clientId, signature)
        val authObj = org.json.JSONObject(authRespJson)
        val authStatus = authObj.optString("status")
        if (authStatus != "Authenticated") {
            val err = authObj.optString("payload", "Authentication rejected by PDS Core")
            throw java.io.IOException("Authentication failed: $err")
        }

        val authPayload = authObj.getJSONObject("payload")
        val sessionId = authPayload.getString("session_id")
        this.activeSessionId = sessionId
        return sessionId
    }

    /**
     * Terminates the current active session.
     */
    fun closeSession(sessionId: String? = null): Boolean {
        val targetSession = sessionId ?: activeSessionId ?: return false
        val req = """{"action":"CloseSession","payload":{"session_id":"$targetSession"}}"""
        val respJson = executeRawRequest(req)
        if (targetSession == activeSessionId) {
            activeSessionId = null
        }
        val respObj = org.json.JSONObject(respJson)
        return respObj.optString("status") == "SessionClosed"
    }

    /**
     * Ensures an active session is available, authenticating if necessary.
     */
    fun ensureSession(): String {
        return activeSessionId ?: authenticate()
    }

    /**
     * Creates an entity in PDS Core.
     * @param entityType The entity collection or type name (e.g. "receipts").
     * @param properties Property map or list conforming to PDS property types.
     * @param sessionId Optional explicit session ID (defaults to active session).
     */
    fun createEntity(
        entityType: String,
        properties: org.json.JSONArray,
        sessionId: String? = null
    ): String {
        val session = sessionId ?: ensureSession()
        val req = """{"action":"CreateEntity","payload":{"session_id":"$session","entity_type":"$entityType","properties":$properties}}"""
        return executeRawRequest(req)
    }

    /**
     * Retrieves an entity by its ULID identifier.
     */
    fun getEntity(entityId: String, sessionId: String? = null): String {
        val session = sessionId ?: ensureSession()
        val req = """{"action":"GetEntity","payload":{"session_id":"$session","entity_id":"$entityId"}}"""
        return executeRawRequest(req)
    }

    /**
     * Updates an existing entity.
     */
    fun updateEntity(
        entityId: String,
        setProperties: org.json.JSONArray? = null,
        removeProperties: org.json.JSONArray? = null,
        sessionId: String? = null
    ): String {
        val session = sessionId ?: ensureSession()
        val setPart = if (setProperties != null) ""","set_properties":$setProperties""" else ""
        val removePart = if (removeProperties != null) ""","remove_properties":$removeProperties""" else ""
        val req = """{"action":"UpdateEntity","payload":{"session_id":"$session","entity_id":"$entityId"$setPart$removePart}}"""
        return executeRawRequest(req)
    }

    /**
     * Deletes an entity by its ULID identifier.
     */
    fun deleteEntity(entityId: String, sessionId: String? = null): String {
        val session = sessionId ?: ensureSession()
        val req = """{"action":"DeleteEntity","payload":{"session_id":"$session","entity_id":"$entityId"}}"""
        return executeRawRequest(req)
    }

    /**
     * High-level put helper compatible with simple key-value/document stores.
     * Formats properties into PDS format and creates entity.
     */
    fun put(entityType: String, dataJson: String, sessionId: String? = null): String {
        val session = sessionId ?: ensureSession()
        val json = org.json.JSONObject(dataJson)
        val propsArray = org.json.JSONArray()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            val valObj = org.json.JSONObject()
            when (value) {
                is Boolean -> valObj.put("Bool", value)
                is Int -> valObj.put("Integer", value)
                is Long -> valObj.put("Integer", value)
                is Double -> valObj.put("Decimal", value.toString())
                is Float -> valObj.put("Decimal", value.toString())
                else -> valObj.put("Text", value.toString())
            }
            val pair = org.json.JSONArray().apply {
                put(key)
                put(valObj)
            }
            propsArray.put(pair)
        }
        return createEntity(entityType, propsArray, session)
    }

    /**
     * Checks if PDS storage engine is ready.
     */
    fun isReady(): Boolean {
        return pdsService?.isReady ?: false
    }

    /**
     * Subscribes to real-time storage changes.
     * @param entityType Specific entity type to watch, or "*" for all entities.
     * @param listener Callback invoked when changes occur.
     */
    fun subscribe(entityType: String = "*", listener: ChangeListener) {
        val service = pdsService ?: throw IllegalStateException("PdsService is not connected")
        val filterJson = """{"entity_type":"$entityType"}"""
        val callback = object : IPdsChangeListener.Stub() {
            override fun onChanges(changeEventsJson: String?) {
                if (changeEventsJson != null) {
                    listener.onChanges(changeEventsJson)
                }
            }
        }
        val entry = ListenerEntry(listener, filterJson, callback)
        activeSubscriptions.add(entry)
        service.registerChangeListener(filterJson, callback)
    }

    /**
     * Unsubscribes a previously registered listener.
     */
    fun unsubscribe(listener: ChangeListener) {
        val entry = activeSubscriptions.firstOrNull { it.listener == listener } ?: return
        activeSubscriptions.remove(entry)
        try {
            pdsService?.unregisterChangeListener(entry.aidlCallback)
        } catch (_: Exception) {}
    }

    /**
     * Retrieves pending changes for an offline/reconnecting client session.
     */
    fun getPendingChanges(sessionId: String, limit: Int? = null): String {
        val limitJson = if (limit != null) ""","limit":$limit""" else ""
        val req = """{"action":"get_pending_changes","session_id":"$sessionId"$limitJson}"""
        return executeRawRequest(req)
    }

    /**
     * Acknowledges processed changes and advances durable client cursor.
     */
    fun acknowledgeChanges(sessionId: String, changeId: String): String {
        val req = """{"action":"acknowledge_changes","session_id":"$sessionId","change_id":"$changeId"}"""
        return executeRawRequest(req)
    }

    /**
     * Reads durable client cursor position.
     */
    fun getClientCursor(sessionId: String): String {
        val req = """{"action":"get_client_cursor","session_id":"$sessionId"}"""
        return executeRawRequest(req)
    }

    /**
     * Creates a binary relation between source and target entities.
     */
    fun createRelation(
        sessionId: String,
        relationType: String,
        sourceEntityId: String,
        targetEntityId: String
    ): String {
        val req = """{"action":"create_relation","session_id":"$sessionId","relation_type":"$relationType","source":"$sourceEntityId","target":"$targetEntityId"}"""
        return executeRawRequest(req)
    }

    /**
     * Creates a multimember relation with custom roles (Engine 1.5).
     * @param membersJson JSON array of members, e.g. '[{"entity_id":"...","role":"author"}]'
     */
    fun createMultimemberRelation(
        sessionId: String,
        relationType: String,
        membersJson: String
    ): String {
        val req = """{"action":"create_relation","session_id":"$sessionId","relation_type":"$relationType","members":$membersJson}"""
        return executeRawRequest(req)
    }

    /**
     * Stores raw binary data as a content-addressed blob.
     * Returns the PDS JSON response containing the BlobDescriptor.
     */
    fun putBlob(sessionId: String, data: ByteArray, mediaType: String? = null): String {
        val mediaTypeJson = if (mediaType != null) ""","media_type":"$mediaType"""" else ""
        val dataArrayJson = data.joinToString(prefix = "[", postfix = "]", separator = ",") { (it.toInt() and 0xFF).toString() }
        val req = """{"action":"put_blob","session_id":"$sessionId","data":$dataArrayJson$mediaTypeJson}"""
        return executeRawRequest(req)
    }

    /**
     * Retrieves raw binary data by blob ID.
     * Returns the byte array of the blob.
     */
    fun getBlob(sessionId: String, blobId: String): ByteArray {
        val req = """{"action":"get_blob","session_id":"$sessionId","blob_id":"$blobId"}"""
        val responseJson = executeRawRequest(req)
        val json = org.json.JSONObject(responseJson)
        if (json.has("status") && json.getString("status") == "Error") {
            throw java.io.IOException("Failed to get blob: ${json.optString("payload")}")
        }
        val payloadArray = json.getJSONArray("payload")
        val bytes = ByteArray(payloadArray.length())
        for (i in 0 until payloadArray.length()) {
            bytes[i] = payloadArray.getInt(i).toByte()
        }
        return bytes
    }

    /**
     * Encrypts plaintext data using client-side AES-256-GCM via AndroidKeyStore
     * and stores the ciphertext as a blob in PDS Core (Zero-Knowledge E2EE).
     */
    fun putEncryptedBlob(
        sessionId: String,
        plaintext: ByteArray,
        keyAlias: String,
        mediaType: String? = "application/octet-stream"
    ): String {
        val encryptedData = PdsCryptoManager.encrypt(plaintext, keyAlias)
        return putBlob(sessionId, encryptedData, mediaType)
    }

    /**
     * Retrieves an encrypted blob from PDS Core and decrypts it client-side
     * using the specified AndroidKeyStore key alias.
     */
    fun getEncryptedBlob(
        sessionId: String,
        blobId: String,
        keyAlias: String
    ): ByteArray {
        val encryptedBytes = getBlob(sessionId, blobId)
        return PdsCryptoManager.decrypt(encryptedBytes, keyAlias)
    }

    /**
     * Lists all registered client applications as JSON array.
     */
    fun listClients(): String {
        val service = pdsService ?: throw IllegalStateException("PdsService not connected")
        return service.listClients()
    }

    /**
     * Revokes permissions of a client application.
     */
    fun revokeClient(clientId: String): Boolean {
        val service = pdsService ?: throw IllegalStateException("PdsService not connected")
        return service.revokeClient(clientId)
    }

    /**
     * Removes a client application registration record.
     */
    fun removeClient(clientId: String): Boolean {
        val service = pdsService ?: throw IllegalStateException("PdsService not connected")
        return service.removeClient(clientId)
    }

    /**
     * Configures the tracing log level filter dynamically on PDS Core.
     * @param logLevel Filter string (e.g. "info", "debug", "trace", "pds=debug").
     */
    fun setLogLevel(logLevel: String): Boolean {
        val service = pdsService ?: throw IllegalStateException("PdsService not connected")
        return service.setLogLevel(logLevel)
    }

    /**
     * Retrieves current tracing log level filter from PDS Core.
     */
    fun getLogLevel(): String {
        val service = pdsService ?: throw IllegalStateException("PdsService not connected")
        return service.logLevel
    }
}
