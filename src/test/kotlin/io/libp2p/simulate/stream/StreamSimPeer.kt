package io.libp2p.simulate.stream

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.simulate.AbstractSimPeer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import io.libp2p.tools.DummyChannel
import io.libp2p.tools.TestChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

abstract class StreamSimPeer<TProtocolController>(
    val isSemiDuplex: Boolean = false
) : AbstractSimPeer() {

    val protocolController: CompletableFuture<TProtocolController> = CompletableFuture()

    var testExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var keyPair = generateKeyPair(KEY_TYPE.ECDSA)

    override fun connectImpl(other: SimPeer): CompletableFuture<SimConnection> {
        other as StreamSimPeer<*>

        val simConnection = if (isSemiDuplex) {
            val connections = connectSemiDuplex(other)
            StreamSimConnection(this, other, connections.first, connections.second)
        } else {
            StreamSimConnection(this, other, connect(other))
        }
        return CompletableFuture.completedFuture(simConnection)
    }

    private fun connect(
        another: StreamSimPeer<*>,
        wireLogs: LogLevel? = null
    ): TestChannel.TestConnection {

        val thisChannel = newChannel("$name=>${another.name}", another, wireLogs, true)
        val anotherChannel = another.newChannel("${another.name}=>$name", this, wireLogs, false)
        return TestChannel.interConnect(thisChannel, anotherChannel)
    }

    private fun connectSemiDuplex(
        another: StreamSimPeer<*>,
        wireLogs: LogLevel? = null
    ): Pair<TestChannel.TestConnection, TestChannel.TestConnection> {
        return connect(another, wireLogs) to
            another.connect(this, wireLogs)
    }

    private fun newChannel(
        channelName: String,
        remote: StreamSimPeer<*>,
        wireLogs: LogLevel? = null,
        initiator: Boolean
    ): TestChannel {

        val parentChannel = DummyChannel().also {
            it.attr(SECURE_SESSION).set(
                SecureChannel.Session(
                    PeerId.fromPubKey(keyPair.second),
                    PeerId.fromPubKey(remote.keyPair.second),
                    remote.keyPair.second
                )
            )
        }

        return TestChannel(
            channelName,
            initiator,
            nettyInitializer { ch ->
                wireLogs?.also { ch.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val connection = Connection(parentChannel)
                ch.attr(CONNECTION).set(connection)
                val stream = Stream(ch, connection)
                getStreamHandler().handleStream(stream).forward(protocolController)
            }
        ).also {
            it.executor = testExecutor
        }
    }

    abstract fun getStreamHandler(): StreamHandler<TProtocolController>

}
