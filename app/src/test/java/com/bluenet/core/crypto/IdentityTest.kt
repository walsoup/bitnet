package com.bluenet.core.crypto

import com.bluenet.core.PeerIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun peerIdIsDerivedFromPublicKey() {
        val identity = Identity.generate()
        assertTrue(PeerIds.isValid(identity.peerId))
        assertEquals(EcKeys.peerIdOf(identity.publicKey), identity.peerId)
        assertEquals(EcKeys.PUBLIC_KEY_SIZE, identity.publicKey.size)
    }

    @Test
    fun restoredIdentityKeepsKeysAndPeerId() {
        val original = Identity.generate()
        val restored = Identity.restore(original.encodePrivate(), original.publicKey)
        assertEquals(original.peerId, restored.peerId)
        assertArrayEquals(original.publicKey, restored.publicKey)
        val signature = restored.sign("hello".toByteArray())
        assertTrue(Identity.verify(original.publicKey, "hello".toByteArray(), signature))
    }

    @Test
    fun signatureRejectsTamperedDataAndOtherKeys() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val signature = alice.sign("message".toByteArray())
        assertTrue(Identity.verify(alice.publicKey, "message".toByteArray(), signature))
        assertFalse(Identity.verify(alice.publicKey, "messagf".toByteArray(), signature))
        assertFalse(Identity.verify(bob.publicKey, "message".toByteArray(), signature))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPointsNotOnTheCurve() {
        val key = Identity.generate().publicKey.copyOf()
        key[64] = (key[64] + 1).toByte()
        EcKeys.decodePublic(key)
    }

    @Test
    fun publicKeyEncodingRoundTrips() {
        repeat(20) {
            val identity = Identity.generate()
            val decoded = EcKeys.decodePublic(identity.publicKey)
            assertArrayEquals(identity.publicKey, EcKeys.encodePublic(decoded))
        }
    }

    @Test
    fun recordCipherRoundTripsAndDetectsReordering() {
        val key = ByteArray(32) { it.toByte() }
        val sender = RecordCipher(key)
        val receiver = RecordCipher(key)
        val first = sender.seal("one".toByteArray())
        val second = sender.seal("two".toByteArray())
        assertArrayEquals("one".toByteArray(), receiver.open(first))
        assertArrayEquals("two".toByteArray(), receiver.open(second))

        val outOfOrder = RecordCipher(key)
        val failed = runCatching { outOfOrder.open(second) }.isFailure
        assertTrue("Opening records out of order must fail", failed)
    }
}
