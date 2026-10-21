package com.linkbridge.android

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Admits accepted sockets for the receive server in two stages, so slow or silent clients
 * can't hold every thread or pile up file descriptors.
 *
 * Stage 1 (handshake) runs the TLS handshake, reads the header and checks the signature. It has
 * a deadline from accept that closes the socket, a bounded queue, and a cap per source address.
 * It returns the stage 2 work (body and approval), or null when the connection is done.
 *
 * Stage 2 (transfer) runs on its own pool, so a transfer waiting for the user's approval
 * doesn't take a handshake thread. Only authenticated, paired devices get this far.
 *
 * A socket refused at either stage is closed.
 */
class ConnectionGate(
    handshakeThreads: Int = 8,
    handshakeQueue: Int = 16,
    transferThreads: Int = 4,
    transferQueue: Int = 4,
    private val maxPerSource: Int = 3,
    private val handshakeDeadlineMs: Long = 10_000
) {
    private val handshakePool = boundedPool(handshakeThreads, handshakeQueue)
    private val transferPool = boundedPool(transferThreads, transferQueue)
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val inHandshake = ConcurrentHashMap<InetAddress, Int>()

    /** Returns false if the socket was refused (and closed). */
    fun submit(socket: Socket, handshake: (Socket) -> (() -> Unit)?): Boolean {
        val source = socket.inetAddress
        if (!reserve(source)) {
            closeQuietly(socket)
            return false
        }
        // Started at accept, so a socket waiting in the queue is covered too.
        val deadline = deadlines.schedule({ closeQuietly(socket) }, handshakeDeadlineMs, TimeUnit.MILLISECONDS)
        try {
            handshakePool.execute {
                val next = try {
                    handshake(socket)
                } catch (error: Exception) {
                    null
                } finally {
                    deadline.cancel(false)
                    release(source)
                }
                if (next == null) {
                    closeQuietly(socket)
                    return@execute
                }
                try {
                    transferPool.execute {
                        try {
                            next()
                        } finally {
                            closeQuietly(socket)
                        }
                    }
                } catch (busy: RejectedExecutionException) {
                    closeQuietly(socket)
                }
            }
        } catch (busy: RejectedExecutionException) {
            deadline.cancel(false)
            release(source)
            closeQuietly(socket)
            return false
        }
        return true
    }

    fun shutdown() {
        handshakePool.shutdownNow()
        transferPool.shutdownNow()
        deadlines.shutdownNow()
    }

    private fun reserve(source: InetAddress): Boolean {
        var admitted = false
        inHandshake.compute(source) { _, count ->
            val current = count ?: 0
            if (current < maxPerSource) {
                admitted = true
                current + 1
            } else {
                current
            }
        }
        return admitted
    }

    private fun release(source: InetAddress) {
        inHandshake.computeIfPresent(source) { _, count -> if (count <= 1) null else count - 1 }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun boundedPool(threads: Int, queue: Int) =
        ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queue))
}
