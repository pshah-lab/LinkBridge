package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On a fresh install the service and the view model both create the identity at once. Both
 * used to find no key and generate one; the Keystore kept the second while the first caller
 * cached the first public key. Pairing then sent key A and ran ECDH with key B: the codes
 * matched, but the two sides stored different secrets (seen on a Galaxy S23 FE).
 */
class PairingKeyStoreTest {
    /** Behaves like AndroidKeyStore: generating under the alias replaces the stored key. */
    private class SlowBackend : PairingKeyBackend {
        @Volatile var stored: KeyPair? = null
        @Volatile var generated = 0

        override fun load(): KeyPair? {
            val key = stored
            if (key == null) Thread.sleep(30) // Keystore lookups take time; widen the window.
            return key
        }

        override fun generate(): KeyPair {
            generated += 1
            return KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair().also { stored = it }
        }
    }

    @Test
    fun concurrentFirstUseYieldsOneKeyThatMatchesTheStoredOne() {
        val backend = SlowBackend()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map {
            pool.submit<String> {
                barrier.await()
                PairingKeyStore(backend).publicKeyBytes().toHex()
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, backend.generated)
        assertEquals(setOf(PairingKeyStore(backend).publicKeyBytes().toHex()), results.toSet())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
