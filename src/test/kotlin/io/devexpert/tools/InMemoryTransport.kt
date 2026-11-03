package io.devexpert.tools

import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * Two transports wired back to back: what one sends, the other receives. Delivery happens inside `send`,
 * so a test needs no sockets, threads or sleeps.
 */
internal class InMemoryTransport : Transport {
    private var peer: InMemoryTransport? = null
    private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeHandler: (() -> Unit)? = null

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage) {
        checkNotNull(peer?.messageHandler) { "peer is not listening" }.invoke(message)
    }

    override suspend fun close() {
        closeHandler?.invoke()
    }

    override fun onClose(block: () -> Unit) {
        closeHandler = block
    }

    override fun onError(block: (Throwable) -> Unit) = Unit

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
    }

    companion object {
        fun linkedPair(): Pair<InMemoryTransport, InMemoryTransport> {
            val a = InMemoryTransport()
            val b = InMemoryTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
