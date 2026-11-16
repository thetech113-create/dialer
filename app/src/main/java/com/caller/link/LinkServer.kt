package com.caller.link

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Listens for the PC. Answers discovery broadcasts on UDP and serves one authenticated TCP
 * connection at a time; a newer connection replaces the previous one.
 */
class LinkServer(
    private val identity: Identity,
    private val store: PairStore,
    private val listener: Listener,
    private val tcpPort: Int = Proto.TCP_PORT,
    private val udpPort: Int = Proto.UDP_PORT,
) {
    /** Callbacks arrive on connection threads, never on the main thread. */
    interface Listener {
        /** Blocks until the user accepts or declines the code shown on both devices. */
        fun onPairRequest(pcName: String, sas: String): Boolean
        fun onConnected(pcId: String, pcName: String)
        fun onDisconnected()
        fun onMessage(msg: JSONObject)
    }

    private class Conn(val socket: Socket, val channel: SecureChannel, val peer: Handshake.Peer)

    private val lock = Any()
    private var active: Conn? = null
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var sender: ExecutorService? = null
    private val pendingDroppable = AtomicInteger()

    @Volatile
    private var running = false

    val boundPort: Int get() = serverSocket?.localPort ?: -1
    val boundUdpPort: Int get() = udpSocket?.localPort ?: -1
    val connectedPcId: String? get() = synchronized(lock) { active?.peer?.id }

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val tcp = ServerSocket()
        try {
            tcp.reuseAddress = true
            tcp.bind(InetSocketAddress(tcpPort))
            if (udpPort >= 0) {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(udpPort))
                }
            }
        } catch (e: IOException) {
            tcp.closeQuietly()
            throw e
        }
        serverSocket = tcp
        sender = Executors.newSingleThreadExecutor()
        running = true
        thread(name = "phon-accept", isDaemon = true) { acceptLoop(tcp) }
        udpSocket?.let { udp -> thread(name = "phon-discovery", isDaemon = true) { discoveryLoop(udp) } }
    }

    fun stop() {
        running = false
        serverSocket?.closeQuietly()
        udpSocket?.close()
        disconnect()
        sender?.shutdownNow()
        serverSocket = null
        udpSocket = null
        sender = null
    }

    /** Drops the current PC connection, if any; the PC is free to reconnect. */
    fun disconnect() {
        synchronized(lock) { active }?.socket?.closeQuietly()
    }

    /**
     * Queues [msg] for the connected PC. Returns false when nobody is connected, or when the
     * message is [droppable] (live audio, worthless late) and the link is already behind.
     */
    fun send(msg: JSONObject, droppable: Boolean = false): Boolean {
        val conn = synchronized(lock) { active } ?: return false
        val executor = sender ?: return false
        if (droppable && pendingDroppable.get() >= MAX_PENDING_DROPPABLE) return false
        if (droppable) pendingDroppable.incrementAndGet()
        return try {
            executor.execute {
                try {
                    conn.channel.send(msg)
                } catch (e: IOException) {
                    conn.socket.closeQuietly()
                } finally {
                    if (droppable) pendingDroppable.decrementAndGet()
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            if (droppable) pendingDroppable.decrementAndGet()
            false
        }
    }

    private fun acceptLoop(tcp: ServerSocket) {
        while (running) {
            val socket = try {
                tcp.accept()
            } catch (e: IOException) {
                if (tcp.isClosed) return
                continue
            }
            thread(name = "phon-conn", isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        var conn: Conn? = null
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val channel = SecureChannel(
                DataInputStream(BufferedInputStream(socket.getInputStream())),
                DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
            )
            val peer = Handshake.server(channel, identity, store, listener::onPairRequest)
            socket.soTimeout = IDLE_TIMEOUT_MS
            conn = Conn(socket, channel, peer)
            val previous = synchronized(lock) { active.also { active = conn } }
            previous?.socket?.closeQuietly()
            listener.onConnected(peer.id, peer.name)
            while (true) {
                val msg = channel.receive()
                if (msg.optString("t") == "ping") {
                    channel.send(JSONObject().put("t", "pong"))
                } else {
                    listener.onMessage(msg)
                }
            }
        } catch (e: Exception) {
            // Timeouts, resets, malformed frames and failed handshakes all end the connection.
        } finally {
            socket.closeQuietly()
            val wasActive = synchronized(lock) {
                if (conn != null && active === conn) {
                    active = null
                    true
                } else {
                    false
                }
            }
            if (wasActive) listener.onDisconnected()
        }
    }

    private fun discoveryLoop(udp: DatagramSocket) {
        val buf = ByteArray(64)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                udp.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (text != Proto.DISCOVER_MAGIC) continue
                val reply = JSONObject().put("t", "phon").put("v", Proto.VERSION)
                    .put("id", identity.id).put("name", identity.name).put("port", boundPort)
                    .toString().toByteArray(Charsets.UTF_8)
                udp.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (e: IOException) {
                if (udp.isClosed) return
            }
        }
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val IDLE_TIMEOUT_MS = 60_000
        const val MAX_PENDING_DROPPABLE = 6
    }
}
