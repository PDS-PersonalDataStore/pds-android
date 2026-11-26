package top.tau2c.pds.client

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Test

class PdsCryptoManagerTest {

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    // Helper using BouncyCastle (reference implementation matching RFC 8032 & Rust ed25519-dalek)
    private fun bcDerivePublicKey(seed: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        return priv.generatePublicKey().encoded
    }

    private fun bcSign(seed: ByteArray, message: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun bcVerify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val pub = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier = Ed25519Signer()
        verifier.init(false, pub)
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    @Test
    fun testBouncyCastleMatchesRfc8032Vectors() {
        // Vector 1
        val seed1 = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expectedPub1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val expectedSig1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        val bcPub1 = bcDerivePublicKey(seed1)
        assertEquals(expectedPub1, bytesToHex(bcPub1))

        val bcSig1 = bcSign(seed1, ByteArray(0))
        assertEquals(expectedSig1, bytesToHex(bcSig1))

        // Vector 2
        val seed2 = hexToBytes("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val expectedPub2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        val expectedSig2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"

        val bcPub2 = bcDerivePublicKey(seed2)
        assertEquals(expectedPub2, bytesToHex(bcPub2))

        val bcSig2 = bcSign(seed2, byteArrayOf(0x72))
        assertEquals(expectedSig2, bytesToHex(bcSig2))
    }

    @Test
    fun testCompareCurrentPdsCryptoManagerWithBouncyCastle() {
        val testSeed = ByteArray(32) { it.toByte() }
        val testMessage = "PDS-CLIENT-AUTH-v1 Test Payload".toByteArray()

        val expectedPub = bcDerivePublicKey(testSeed)
        val expectedSig = bcSign(testSeed, testMessage)

        val actualPub = PdsCryptoManager.derivePublicKey(testSeed)
        val actualSig = PdsCryptoManager.signWithPrivateKey(testSeed, testMessage)

        // Compare derived public key
        println("Expected PubKey (BC/Rust): " + bytesToHex(expectedPub))
        println("Actual PubKey (PdsCrypto):  " + bytesToHex(actualPub))
        assertEquals("Public keys must match", bytesToHex(expectedPub), bytesToHex(actualPub))

        // Compare signature
        println("Expected Sig (BC/Rust): " + bytesToHex(expectedSig))
        println("Actual Sig (PdsCrypto):  " + bytesToHex(actualSig))
        assertEquals("Signatures must match", bytesToHex(expectedSig), bytesToHex(actualSig))
    }
}
