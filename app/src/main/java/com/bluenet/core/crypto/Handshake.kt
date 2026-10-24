package com.bluenet.core.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HandshakeException(message: String) : IOException(message)

class HandshakeResult(
    val remotePeerId: String,
    val remotePublicKey: ByteArray,
    val sendCipher: RecordCipher,
    val receiveCipher: RecordCipher,
    /** Hash of both hello messages; each side proves key possession by sending it encrypted. */
    val transcriptHash: ByteArray,
)

/**
 * Mutually authenticated key exchange for a point-to-point link.
 *
 * Each side sends `version || ephemeral public key || static public key`. The session keys are
 * derived from three ECDH results: ephemeral-ephemeral (forward secrecy) plus each side's
 * ephemeral key against the other's static key. Only the holders of both static private keys
 * can derive the same keys, so a successful exchange of encrypted records authenticates both
 * peers, and the peer ID is bound to the static key by [EcKeys.peerIdOf].
 */
object Handshake {
    const val VERSION: Byte = 2
    private const val HELLO_SIZE = 1 + EcKeys.PUBLIC_KEY_SIZE * 2
    private val SALT_LABEL = "BlueNet link v2".toByteArray()
    private val KEY_INFO = "BlueNet session keys".toByteArray()

    fun initiate(
        input: DataInputStream,
        output: DataOutputStream,
        identity: Identity,
        expectedPeerId: String?,
    ): HandshakeResult {
        val ephemeral = EcKeys.generateKeyPair()
        val hello1 = hello(ephemeral, identity)
        writeMessage(output, hello1)
        val hello2 = readMessage(input)
        val remote = parseHello(hello2)

        if (expectedPeerId != null && remote.peerId != expectedPeerId) {
            throw HandshakeException("Peer identity mismatch: expected $expectedPeerId, got ${remote.peerId}")
        }
        rejectSelf(remote, identity)

        val ee = EcKeys.agree(ephemeral.private, remote.ephemeral)
        val es = EcKeys.agree(ephemeral.private, remote.static)
        val se = EcKeys.agree(identity.keyPair.private, remote.ephemeral)
        val keys = deriveKeys(ee + es + se, hello1, hello2)
        return HandshakeResult(remote.peerId, remote.staticBytes, RecordCipher(keys.initiatorToResponder), RecordCipher(keys.responderToInitiator), keys.transcriptHash)
    }

    fun respond(input: DataInputStream, output: DataOutputStream, identity: Identity): HandshakeResult {
        val hello1 = readMessage(input)
        val remote = parseHello(hello1)
        rejectSelf(remote, identity)

        val ephemeral = EcKeys.generateKeyPair()
        val hello2 = hello(ephemeral, identity)
        writeMessage(output, hello2)

        val ee = EcKeys.agree(ephemeral.private, remote.ephemeral)
        val es = EcKeys.agree(identity.keyPair.private, remote.ephemeral)
        val se = EcKeys.agree(ephemeral.private, remote.static)
        val keys = deriveKeys(ee + es + se, hello1, hello2)
        return HandshakeResult(remote.peerId, remote.staticBytes, RecordCipher(keys.responderToInitiator), RecordCipher(keys.initiatorToResponder), keys.transcriptHash)
    }

    private class RemoteHello(val ephemeral: PublicKey, val static: PublicKey, val staticBytes: ByteArray) {
        val peerId: String = EcKeys.peerIdOf(staticBytes)
    }

    private class SessionKeys(val initiatorToResponder: ByteArray, val responderToInitiator: ByteArray, val transcriptHash: ByteArray)

    private fun rejectSelf(remote: RemoteHello, identity: Identity) {
        if (remote.peerId == identity.peerId) throw HandshakeException("Refusing to link with own identity")
    }

    private fun hello(ephemeral: KeyPair, identity: Identity): ByteArray =
        byteArrayOf(VERSION) + EcKeys.encodePublic(ephemeral.public) + identity.publicKey

    private fun parseHello(message: ByteArray): RemoteHello {
        if (message.size != HELLO_SIZE) throw HandshakeException("Malformed hello (${message.size} bytes)")
        if (message[0] != VERSION) throw HandshakeException("Unsupported protocol version ${message[0]}")
        val ephemeralBytes = message.copyOfRange(1, 1 + EcKeys.PUBLIC_KEY_SIZE)
        val staticBytes = message.copyOfRange(1 + EcKeys.PUBLIC_KEY_SIZE, HELLO_SIZE)
        return try {
            RemoteHello(EcKeys.decodePublic(ephemeralBytes), EcKeys.decodePublic(staticBytes), staticBytes)
        } catch (e: IllegalArgumentException) {
            throw HandshakeException("Invalid public key: ${e.message}")
        }
    }

    private fun deriveKeys(ikm: ByteArray, hello1: ByteArray, hello2: ByteArray): SessionKeys {
        val salt = MessageDigest.getInstance("SHA-256").run {
            update(SALT_LABEL)
            update(hello1)
            update(hello2)
            digest()
        }
        val okm = Hkdf.derive(salt, ikm, KEY_INFO, 64)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64), salt)
    }

    private fun writeMessage(output: DataOutputStream, body: ByteArray) {
        output.writeShort(body.size)
        output.write(body)
        output.flush()
    }

    private fun readMessage(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        if (length > 1024) throw HandshakeException("Handshake message too large ($length bytes)")
        return ByteArray(length).also { input.readFully(it) }
    }
}

object Hkdf {
    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, n)
            produced += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/**
 * AES-256-GCM for one direction of a link. Nonces are a per-direction counter, so records must
 * be sealed and opened in order by a single thread each.
 */
class RecordCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    fun seal(plaintext: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nextNonce()))
        return cipher.doFinal(plaintext)
    }

    /** @throws javax.crypto.AEADBadTagException if the record was tampered with or reordered. */
    fun open(ciphertext: ByteArray): ByteArray {
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nextNonce()))
        return cipher.doFinal(ciphertext)
    }

    private fun nextNonce(): ByteArray {
        val nonce = ByteArray(12)
        val value = counter++
        for (i in 0 until 8) nonce[4 + i] = (value ushr (56 - 8 * i)).toByte()
        return nonce
    }

    companion object {
        const val TAG_BITS = 128
        const val OVERHEAD = TAG_BITS / 8
    }
}
