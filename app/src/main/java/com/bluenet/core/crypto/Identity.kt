package com.bluenet.core.crypto

import com.bluenet.core.PeerIds
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A device's long-term P-256 key pair. The public key travels as a 65-byte uncompressed point,
 * and the peer ID is derived from it (see [PeerIds]).
 */
class Identity(val keyPair: KeyPair) {

    val publicKey: ByteArray = EcKeys.encodePublic(keyPair.public)
    val peerId: String = EcKeys.peerIdOf(publicKey)

    fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    fun encodePrivate(): ByteArray = keyPair.private.encoded

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun generate(): Identity = Identity(EcKeys.generateKeyPair())

        fun restore(privatePkcs8: ByteArray, publicKey: ByteArray): Identity {
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
            return Identity(KeyPair(EcKeys.decodePublic(publicKey), private))
        }

        fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(EcKeys.decodePublic(publicKey))
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}

object EcKeys {
    const val PUBLIC_KEY_SIZE = 65
    private const val COORDINATE_SIZE = 32

    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    fun peerIdOf(publicKey: ByteArray): String =
        PeerIds.fromBytes(MessageDigest.getInstance("SHA-256").digest(publicKey))

    fun encodePublic(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        val out = ByteArray(PUBLIC_KEY_SIZE)
        out[0] = 0x04
        writeCoordinate(point.affineX, out, 1)
        writeCoordinate(point.affineY, out, 1 + COORDINATE_SIZE)
        return out
    }

    /** Decodes and validates an uncompressed point; rejects anything not on P-256. */
    fun decodePublic(encoded: ByteArray): PublicKey {
        require(encoded.size == PUBLIC_KEY_SIZE && encoded[0] == 0x04.toByte()) { "Not an uncompressed P-256 point" }
        val x = BigInteger(1, encoded.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, encoded.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
        require(isOnCurve(x, y)) { "Point is not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        return lhs == rhs
    }

    private fun writeCoordinate(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val start = if (bytes.size > COORDINATE_SIZE) bytes.size - COORDINATE_SIZE else 0
        val length = bytes.size - start
        System.arraycopy(bytes, start, out, offset + COORDINATE_SIZE - length, length)
    }

    fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        javax.crypto.KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
}
