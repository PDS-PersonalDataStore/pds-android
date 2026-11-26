package top.tau2c.pds.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Client-side cryptographic manager for End-to-End Encryption (E2EE) / Zero-Knowledge storage.
 * Manages AES-256-GCM symmetric keys backed by the hardware AndroidKeyStore provider.
 */
object PdsCryptoManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /**
     * Gets an existing AES key or generates a new one in the Android Keystore.
     */
    fun getOrCreateKey(keyAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * The resulting byte array prepends the 12-byte IV followed by the ciphertext + authentication tag.
     */
    fun encrypt(plaintext: ByteArray, keyAlias: String): ByteArray {
        val secretKey = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv // 12 bytes
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts an encrypted payload (12-byte IV + ciphertext + authentication tag) using AES-256-GCM.
     */
    fun decrypt(encryptedPayload: ByteArray, keyAlias: String): ByteArray {
        require(encryptedPayload.size > GCM_IV_LENGTH) { "Ciphertext too short" }
        val secretKey = getOrCreateKey(keyAlias)
        val iv = encryptedPayload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedPayload.copyOfRange(GCM_IV_LENGTH, encryptedPayload.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    private const val PREFS_NAME = "pds_client_identity"
    private const val KEY_CLIENT_ID = "pds_client_id"
    private const val KEY_PRIVATE_KEY = "pds_private_key"
    private const val KEY_PUBLIC_KEY = "pds_public_key"

    /**
     * Gets or creates a 32-byte Ed25519-compatible identity private seed in private storage,
     * encrypted with a master key from AndroidKeyStore.
     */
    @Synchronized
    fun getOrCreateIdentityPrivateKey(context: android.content.Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val encryptedHex = prefs.getString(KEY_PRIVATE_KEY, null)
        if (encryptedHex != null) {
            val encryptedBytes = hexToBytes(encryptedHex)
            return decrypt(encryptedBytes, "pds_identity_master_key")
        }

        // Generate 32 bytes cryptographically secure random private seed
        val privateKey = ByteArray(32).apply {
            java.security.SecureRandom().nextBytes(this)
        }

        val encryptedBytes = encrypt(privateKey, "pds_identity_master_key")
        prefs.edit().putString(KEY_PRIVATE_KEY, bytesToHex(encryptedBytes)).apply()

        // Derive and store public key
        val publicKey = derivePublicKeyFromSeed(privateKey)
        prefs.edit().putString(KEY_PUBLIC_KEY, bytesToHex(publicKey)).apply()

        return privateKey
    }

    /**
     * Retrieves the 32-byte public key corresponding to the client identity key.
     */
    @Synchronized
    fun getOrCreateIdentityPublicKey(context: android.content.Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val pubHex = prefs.getString(KEY_PUBLIC_KEY, null)
        if (pubHex != null) {
            return hexToBytes(pubHex)
        }
        val privKey = getOrCreateIdentityPrivateKey(context)
        val pubKey = derivePublicKeyFromSeed(privKey)
        prefs.edit().putString(KEY_PUBLIC_KEY, bytesToHex(pubKey)).apply()
        return pubKey
    }

    /**
     * Explicitly saves a 32-byte Ed25519 private key seed into encrypted private storage.
     */
    @Synchronized
    fun saveIdentityPrivateKey(context: android.content.Context, privateKeySeed: ByteArray) {
        require(privateKeySeed.size == 32) { "Ed25519 private key seed must be exactly 32 bytes" }
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val encryptedBytes = encrypt(privateKeySeed, "pds_identity_master_key")
        prefs.edit().putString(KEY_PRIVATE_KEY, bytesToHex(encryptedBytes)).apply()

        // Derive and store matching public key
        val publicKey = derivePublicKeyFromSeed(privateKeySeed)
        prefs.edit().putString(KEY_PUBLIC_KEY, bytesToHex(publicKey)).apply()
    }

    /**
     * Stores the registered clientId returned from PDS authorization.
     */
    fun saveClientId(context: android.content.Context, clientId: String) {

        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
    }

    /**
     * Returns the stored clientId, or null if not yet authorized.
     */
    fun getClientId(context: android.content.Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_CLIENT_ID, null)
    }

    /**
     * Clears stored client identity and authorization details.
     */
    fun clearClientIdentity(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CLIENT_ID).remove(KEY_PUBLIC_KEY).remove(KEY_PRIVATE_KEY).apply()
    }

    /**
     * Signs a message using Ed25519 with the client's private identity key from context.
     */
    fun sign(context: android.content.Context, message: ByteArray): ByteArray {
        val privSeed = getOrCreateIdentityPrivateKey(context)
        return signWithPrivateKey(privSeed, message)
    }

    /**
     * Signs a message using Ed25519 with a manually provided 32-byte private key seed.
     */
    fun signWithPrivateKey(privateKeySeed: ByteArray, message: ByteArray): ByteArray {
        require(privateKeySeed.size == 32) { "Ed25519 private key seed must be exactly 32 bytes" }
        return signEd25519(privateKeySeed, message)
    }

    /**
     * Derives a 32-byte Ed25519 public key from a 32-byte private key seed.
     */
    fun derivePublicKey(privateKeySeed: ByteArray): ByteArray {
        require(privateKeySeed.size == 32) { "Ed25519 private key seed must be exactly 32 bytes" }
        return derivePublicKeyFromSeed(privateKeySeed)
    }

    /**
     * Computes the signing payload according to PDS specification:
     * "PDS-CLIENT-AUTH-v1" + server_instance_id + client_id (16 bytes ULID) + client_nonce (32 bytes) + server_nonce (32 bytes) + api_version
     */
    fun computeSigningPayload(
        serverInstanceId: String,
        clientIdUlidString: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        apiVersion: String
    ): ByteArray {
        val authTag = "PDS-CLIENT-AUTH-v1".toByteArray(Charsets.UTF_8)
        val srvBytes = serverInstanceId.toByteArray(Charsets.UTF_8)
        val clientIdBytes = parseUlidTo16Bytes(clientIdUlidString)
        val verBytes = apiVersion.toByteArray(Charsets.UTF_8)

        val totalLen = authTag.size + srvBytes.size + clientIdBytes.size + clientNonce.size + serverNonce.size + verBytes.size
        val out = ByteArray(totalLen)
        var offset = 0

        System.arraycopy(authTag, 0, out, offset, authTag.size)
        offset += authTag.size

        System.arraycopy(srvBytes, 0, out, offset, srvBytes.size)
        offset += srvBytes.size

        System.arraycopy(clientIdBytes, 0, out, offset, clientIdBytes.size)
        offset += clientIdBytes.size

        System.arraycopy(clientNonce, 0, out, offset, clientNonce.size)
        offset += clientNonce.size

        System.arraycopy(serverNonce, 0, out, offset, serverNonce.size)
        offset += serverNonce.size

        System.arraycopy(verBytes, 0, out, offset, verBytes.size)
        return out
    }

    private fun parseUlidTo16Bytes(ulidString: String): ByteArray {
        // Crockford Base32 decoding of 26-char ULID into 16 bytes
        val crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var hi = 0L
        var lo = 0L

        val clean = ulidString.uppercase()
        require(clean.length == 26) { "Invalid ULID length: ${clean.length}" }

        // First 10 chars (48 bits / timestamp) + 3 chars (15 bits) = 63 bits
        for (i in 0 until 10) {
            val v = crockford.indexOf(clean[i])
            require(v >= 0) { "Invalid char in ULID: ${clean[i]}" }
            hi = (hi shl 5) or v.toLong()
        }
        for (i in 10 until 13) {
            val v = crockford.indexOf(clean[i])
            require(v >= 0) { "Invalid char in ULID: ${clean[i]}" }
            hi = (hi shl 5) or v.toLong()
        }

        // Remaining 13 chars (65 bits: 1 bit into hi, 64 into lo)
        val mid = crockford.indexOf(clean[13])
        require(mid >= 0) { "Invalid char in ULID: ${clean[13]}" }
        hi = (hi shl 1) or ((mid.toLong() shr 4) and 1L)
        lo = (mid.toLong() and 0x0F)
        for (i in 14 until 26) {
            val v = crockford.indexOf(clean[i])
            require(v >= 0) { "Invalid char in ULID: ${clean[i]}" }
            lo = (lo shl 5) or v.toLong()
        }

        val out = ByteArray(16)
        for (i in 7 downTo 0) {
            out[i] = (hi and 0xFF).toByte()
            hi = hi ushr 8
        }
        for (i in 15 downTo 8) {
            out[i] = (lo and 0xFF).toByte()
            lo = lo ushr 8
        }
        return out
    }

    private fun signEd25519(seed: ByteArray, message: ByteArray): ByteArray {
        val privParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun derivePublicKeyFromSeed(seed: ByteArray): ByteArray {
        val privParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        return privParams.generatePublicKey().encoded
    }


    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
