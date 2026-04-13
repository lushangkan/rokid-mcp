package cn.cutemc.rokidmcp.glasses.gateway

import android.bluetooth.BluetoothDevice
import android.util.Log
import cn.cutemc.rokidmcp.glasses.logging.assertLog
import cn.cutemc.rokidmcp.glasses.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.glasses.logging.captureTimberLogs
import cn.cutemc.rokidmcp.share.protocol.constants.CommandAction
import cn.cutemc.rokidmcp.share.protocol.local.DefaultLocalFrameCodec
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommand
import cn.cutemc.rokidmcp.share.protocol.local.DisplayTextCommandParams
import cn.cutemc.rokidmcp.share.protocol.local.HelloPayload
import cn.cutemc.rokidmcp.share.protocol.local.LinkRole
import cn.cutemc.rokidmcp.share.protocol.local.LocalFrameHeader
import cn.cutemc.rokidmcp.share.protocol.local.LocalMessageType
import cn.cutemc.rokidmcp.share.protocol.local.PingPayload
import cn.cutemc.rokidmcp.share.protocol.local.PongPayload
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RfcommServerTransportTest {
    private val codec = DefaultLocalFrameCodec()

    @Test
    fun `start logs permission denial with rfcomm-server tag`() {
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { false },
            adapterProvider = { error("adapter should not be requested") },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )

        val logs = captureTimberLogs {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { transport.start() }
            }
        }

        transportScope.cancel()

        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "validating BLUETOOTH_CONNECT permission")
        logs.assertLog(Log.WARN, RFCOMM_SERVER_TAG, "BLUETOOTH_CONNECT permission denied")
        assertEquals(GlassesTransportState.IDLE, transport.state.value)
    }

    @Test
    fun `transport traces listen accept frame metadata disconnect and cleanup with masked addresses`() {
        val serverSocket = FakeServerSocket()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = { FakeRfcommBluetoothAdapter(serverSocket) },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )
        val unbondedClient = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "AA:BB:CC:DD:EE:FF",
                bondState = BluetoothDevice.BOND_NONE,
            ),
            input = ControlledInputStream(),
            output = RecordingOutputStream(),
        )
        val bondedInput = ControlledInputStream()
        val bondedOutput = RecordingOutputStream()
        val bondedClient = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "12:34:56:78:9A:BC",
                bondState = BluetoothDevice.BOND_BONDED,
            ),
            input = bondedInput,
            output = bondedOutput,
        )
        val sendHeader = LocalFrameHeader(
            type = LocalMessageType.PONG,
            timestamp = 101L,
            payload = PongPayload(seq = 7L, nonce = "pong-7"),
        )
        val receivedHeader = LocalFrameHeader(
            type = LocalMessageType.PING,
            timestamp = 102L,
            payload = PingPayload(seq = 8L, nonce = "ping-8"),
        )

        val logs = captureTimberLogs { tree ->
            serverSocket.enqueueAcceptedClient(unbondedClient)
            serverSocket.enqueueAcceptedClient(bondedClient)

            runBlocking { transport.start() }

            waitUntil("transport listening") {
                transport.state.value == GlassesTransportState.LISTENING &&
                    tree.logs.any { entry -> entry.message.contains("state -> LISTENING") }
            }
            waitUntil("unbonded client rejection") {
                tree.logs.any { entry -> entry.message.contains("rejected unbonded RFCOMM client") }
            }
            waitUntil("bonded client attach") {
                transport.state.value == GlassesTransportState.CONNECTED &&
                    tree.logs.any { entry -> entry.message.contains("state -> CONNECTED") }
            }

            runBlocking { transport.send(sendHeader) }

            bondedInput.enqueue(codec.encode(receivedHeader))
            waitUntil("received frame log") {
                tree.logs.any { entry -> entry.message.contains("frame received type=PING") }
            }

            bondedInput.finish()
            waitUntil("client disconnect") {
                transport.state.value == GlassesTransportState.LISTENING &&
                    tree.logs.any { entry -> entry.message.contains("RFCOMM client disconnected") }
            }

            runBlocking { transport.stop("test-stop") }
            waitUntil("transport stopped") {
                transport.state.value == GlassesTransportState.DISCONNECTED &&
                    tree.logs.any { entry -> entry.message.contains("state -> DISCONNECTED") }
            }
        }

        transportScope.cancel()

        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "validating BLUETOOTH_CONNECT permission")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "checking bluetooth adapter availability")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "creating RFCOMM listen socket")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "RFCOMM listen socket created")
        logs.assertLog(Log.INFO, RFCOMM_SERVER_TAG, "state -> LISTENING")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "waiting for RFCOMM client")
        logs.assertLog(Log.WARN, RFCOMM_SERVER_TAG, "rejected unbonded RFCOMM client device=**:**:**:**:EE:FF")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "accepted bonded RFCOMM client device=**:**:**:**:9A:BC")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "attaching RFCOMM client device=**:**:**:**:9A:BC")
        logs.assertLog(Log.INFO, RFCOMM_SERVER_TAG, "state -> CONNECTED")
        logs.assertLog(Log.VERBOSE, RFCOMM_SERVER_TAG, "frame sent type=PONG")
        logs.assertLog(Log.VERBOSE, RFCOMM_SERVER_TAG, "frame received type=PING")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "RFCOMM client disconnected")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "cleaning up RFCOMM sockets reason=stop:test-stop")
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "state -> DISCONNECTED")
        logs.assertNoSensitiveData()
        assertTrue(logs.none { entry -> "AA:BB:CC:DD:EE:FF" in entry.message })
        assertTrue(logs.none { entry -> "12:34:56:78:9A:BC" in entry.message })
        assertTrue(bondedOutput.writtenBytes.isNotEmpty())
    }

    @Test
    fun `start logs listen socket failure and transitions to error`() {
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = {
                object : RfcommBluetoothAdapter {
                    override fun listen(serviceName: String, serviceUuid: UUID): RfcommServerSocketHandle {
                        throw IOException("listen boom")
                    }
                }
            },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )

        val logs = captureTimberLogs {
            assertThrows(IOException::class.java) {
                runBlocking { transport.start() }
            }
        }

        transportScope.cancel()

        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "creating RFCOMM listen socket")
        logs.assertLog(Log.ERROR, RFCOMM_SERVER_TAG, "failed to start RFCOMM server transport")
        logs.assertLog(Log.ERROR, RFCOMM_SERVER_TAG, "state -> ERROR")
        assertEquals(GlassesTransportState.ERROR, transport.state.value)
    }

    @Test
    fun `fragmented inbound frames are buffered until complete`() {
        val serverSocket = FakeServerSocket()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = { FakeRfcommBluetoothAdapter(serverSocket) },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )
        val clientInput = ControlledInputStream()
        val client = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "12:34:56:78:9A:BC",
                bondState = BluetoothDevice.BOND_BONDED,
            ),
            input = clientInput,
            output = RecordingOutputStream(),
        )
        val events = CopyOnWriteArrayList<GlassesTransportEvent>()
        val eventJob = transportScope.launch {
            transport.events.collect { events += it }
        }

        serverSocket.enqueueAcceptedClient(client)

        runBlocking { transport.start() }
        waitUntil("bonded client attach") {
            transport.state.value == GlassesTransportState.CONNECTED
        }

        val helloHeader = LocalFrameHeader(
            type = LocalMessageType.HELLO,
            timestamp = 201L,
            payload = HelloPayload(
                role = LinkRole.PHONE,
                deviceId = "phone-1",
                appVersion = "1.0.0",
                appBuild = "100",
                supportedActions = listOf(CommandAction.DISPLAY_TEXT),
            ),
        )
        val helloFrame = codec.encode(helloHeader)
        clientInput.enqueue(helloFrame.copyOfRange(0, 8))
        Thread.sleep(100L)
        assertTrue(events.filterIsInstance<GlassesTransportEvent.FrameReceived>().isEmpty())

        clientInput.enqueue(helloFrame.copyOfRange(8, helloFrame.size))
        waitUntil("fragmented hello frame") {
            events.filterIsInstance<GlassesTransportEvent.FrameReceived>().size == 1
        }

        val commandHeader = LocalFrameHeader(
            type = LocalMessageType.COMMAND,
            requestId = "cmd-1",
            timestamp = 202L,
            payload = DisplayTextCommand(
                timeoutMs = 3_000L,
                params = DisplayTextCommandParams(
                    text = "hello glasses",
                    durationMs = 1_500L,
                ),
            ),
        )
        val commandFrame = codec.encode(commandHeader)
        val firstCut = commandFrame.size / 3
        val secondCut = (commandFrame.size * 2) / 3
        clientInput.enqueue(commandFrame.copyOfRange(0, firstCut))
        Thread.sleep(100L)
        assertEquals(1, events.filterIsInstance<GlassesTransportEvent.FrameReceived>().size)

        clientInput.enqueue(commandFrame.copyOfRange(firstCut, secondCut))
        Thread.sleep(100L)
        assertEquals(1, events.filterIsInstance<GlassesTransportEvent.FrameReceived>().size)

        clientInput.enqueue(commandFrame.copyOfRange(secondCut, commandFrame.size))
        waitUntil("fragmented command frame") {
            events.filterIsInstance<GlassesTransportEvent.FrameReceived>().size == 2
        }

        runBlocking { transport.stop("test-stop") }
        transportScope.cancel()
        runBlocking { eventJob.join() }

        val frames = events.filterIsInstance<GlassesTransportEvent.FrameReceived>()
        assertEquals(listOf(helloHeader, commandHeader), frames.map { it.header })
    }

    @Test
    fun `coalesced inbound frames produce ordered frame received events`() {
        val serverSocket = FakeServerSocket()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = { FakeRfcommBluetoothAdapter(serverSocket) },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )
        val clientInput = ControlledInputStream()
        val client = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "12:34:56:78:9A:BC",
                bondState = BluetoothDevice.BOND_BONDED,
            ),
            input = clientInput,
            output = RecordingOutputStream(),
        )
        val events = CopyOnWriteArrayList<GlassesTransportEvent>()
        val eventJob = transportScope.launch {
            transport.events.collect { events += it }
        }

        serverSocket.enqueueAcceptedClient(client)

        runBlocking { transport.start() }
        waitUntil("bonded client attach") {
            transport.state.value == GlassesTransportState.CONNECTED
        }

        val firstHeader = LocalFrameHeader(
            type = LocalMessageType.PING,
            timestamp = 301L,
            payload = PingPayload(seq = 1L, nonce = "ping-1"),
        )
        val secondHeader = LocalFrameHeader(
            type = LocalMessageType.PONG,
            timestamp = 302L,
            payload = PongPayload(seq = 1L, nonce = "pong-1"),
        )
        clientInput.enqueue(codec.encode(firstHeader) + codec.encode(secondHeader))

        waitUntil("coalesced frame delivery") {
            events.filterIsInstance<GlassesTransportEvent.FrameReceived>().size == 2
        }

        runBlocking { transport.stop("test-stop") }
        transportScope.cancel()
        runBlocking { eventJob.join() }

        val frames = events.filterIsInstance<GlassesTransportEvent.FrameReceived>()
        assertEquals(listOf(firstHeader, secondHeader), frames.map { it.header })
    }

    @Test
    fun `malformed complete frame decode failure stays visible at rfcomm caller site`() {
        val serverSocket = FakeServerSocket()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = { FakeRfcommBluetoothAdapter(serverSocket) },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )
        val clientInput = ControlledInputStream()
        val client = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "12:34:56:78:9A:BC",
                bondState = BluetoothDevice.BOND_BONDED,
            ),
            input = clientInput,
            output = RecordingOutputStream(),
        )
        val events = CopyOnWriteArrayList<GlassesTransportEvent>()
        val eventJob = transportScope.launch {
            transport.events.collect { events += it }
        }
        val malformedFrame = codec.encode(
            LocalFrameHeader(
                type = LocalMessageType.PING,
                timestamp = 401L,
                payload = PingPayload(seq = 9L, nonce = "ping-9"),
            ),
        ).apply {
            this[0] = 0x00.toByte()
        }

        val logs = captureTimberLogs { tree ->
            serverSocket.enqueueAcceptedClient(client)

            runBlocking { transport.start() }

            waitUntil("bonded client attach") {
                transport.state.value == GlassesTransportState.CONNECTED &&
                    tree.logs.any { entry -> entry.message.contains("state -> CONNECTED") }
            }

            clientInput.enqueue(malformedFrame)
            waitUntil("frame decode failure") {
                transport.state.value == GlassesTransportState.ERROR &&
                    events.any { it is GlassesTransportEvent.Failure } &&
                    tree.logs.any { entry -> entry.message.contains("RFCOMM server client connection failed") }
            }

            runBlocking { transport.stop("test-stop") }
        }

        transportScope.cancel()
        runBlocking { eventJob.join() }

        val failure = events.filterIsInstance<GlassesTransportEvent.Failure>().single()
        assertTrue(failure.cause.message?.contains("rfcomm server frame decode failed") == true)
        assertTrue(failure.cause.cause?.message?.contains("Unexpected frame magic") == true)
        logs.assertLog(Log.ERROR, RFCOMM_SERVER_TAG, "RFCOMM server client connection failed")
        logs.assertLog(Log.ERROR, RFCOMM_SERVER_TAG, "state -> ERROR")
        logs.assertNoSensitiveData()
        assertTrue(logs.any { entry -> entry.throwable?.cause?.message?.contains("Unexpected frame magic") == true })
    }

    @Test
    fun `android bluetooth read minus one exception is treated as disconnect`() {
        val serverSocket = FakeServerSocket()
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = AndroidRfcommServerTransport(
            permissionChecker = { true },
            adapterProvider = { FakeRfcommBluetoothAdapter(serverSocket) },
            ioDispatcher = Dispatchers.IO,
            transportScope = transportScope,
            codec = codec,
        )
        val clientInput = ControlledInputStream()
        val client = FakeClientSocket(
            remoteDevice = RfcommRemoteDeviceInfo(
                address = "12:34:56:78:9A:BC",
                bondState = BluetoothDevice.BOND_BONDED,
            ),
            input = clientInput,
            output = RecordingOutputStream(),
        )
        val events = CopyOnWriteArrayList<GlassesTransportEvent>()
        val eventJob = transportScope.launch {
            transport.events.collect { events += it }
        }

        val logs = captureTimberLogs { tree ->
            serverSocket.enqueueAcceptedClient(client)

            runBlocking { transport.start() }

            waitUntil("bonded client attach") {
                transport.state.value == GlassesTransportState.CONNECTED &&
                    tree.logs.any { entry -> entry.message.contains("state -> CONNECTED") }
            }

            clientInput.fail(IOException("bt socket closed, read return: -1"))

            waitUntil("client disconnect after bluetooth read close") {
                transport.state.value == GlassesTransportState.LISTENING &&
                    events.any { it is GlassesTransportEvent.ConnectionClosed } &&
                    tree.logs.any { entry -> entry.message.contains("RFCOMM client disconnected") }
            }

            runBlocking { transport.stop("test-stop") }
        }

        transportScope.cancel()
        runBlocking { eventJob.join() }

        assertTrue(events.none { it is GlassesTransportEvent.Failure })
        logs.assertLog(Log.DEBUG, RFCOMM_SERVER_TAG, "RFCOMM client disconnected")
        assertTrue(logs.none { entry -> entry.message.contains("RFCOMM server client connection failed") })
        assertTrue(logs.none { entry -> entry.message.contains("state -> ERROR") })
    }
}

private const val RFCOMM_SERVER_TAG = "rfcomm-server"

private class FakeRfcommBluetoothAdapter(
    private val serverSocket: RfcommServerSocketHandle,
) : RfcommBluetoothAdapter {
    override fun listen(serviceName: String, serviceUuid: UUID): RfcommServerSocketHandle = serverSocket
}

private class FakeServerSocket : RfcommServerSocketHandle {
    private sealed interface AcceptAction {
        data class Return(val clientSocket: RfcommClientSocketHandle) : AcceptAction

        data object Close : AcceptAction
    }

    private val acceptActions = LinkedBlockingQueue<AcceptAction>()
    @Volatile
    private var closed = false

    fun enqueueAcceptedClient(clientSocket: RfcommClientSocketHandle) {
        acceptActions.put(AcceptAction.Return(clientSocket))
    }

    override fun accept(): RfcommClientSocketHandle {
        while (!closed) {
            when (val action = acceptActions.poll(100, TimeUnit.MILLISECONDS) ?: continue) {
                is AcceptAction.Return -> return action.clientSocket
                AcceptAction.Close -> throw IOException("server socket closed")
            }
        }

        throw IOException("server socket closed")
    }

    override fun close() {
        closed = true
        acceptActions.offer(AcceptAction.Close)
    }
}

private class FakeClientSocket(
    override val remoteDevice: RfcommRemoteDeviceInfo?,
    private val input: ControlledInputStream,
    private val output: RecordingOutputStream,
) : RfcommClientSocketHandle {
    override val inputStream: InputStream
        get() = input

    override val outputStream: OutputStream
        get() = output

    override fun close() {
        input.close()
        output.close()
    }
}

private class ControlledInputStream : InputStream() {
    private sealed interface ReadAction {
        data class Bytes(val value: ByteArray) : ReadAction

        data class Error(val exception: IOException) : ReadAction

        data object End : ReadAction
    }

    private val readActions = LinkedBlockingQueue<ReadAction>()
    @Volatile
    private var closed = false

    fun enqueue(bytes: ByteArray) {
        readActions.put(ReadAction.Bytes(bytes))
    }

    fun finish() {
        readActions.put(ReadAction.End)
    }

    fun fail(exception: IOException) {
        readActions.put(ReadAction.Error(exception))
    }

    override fun read(): Int {
        throw UnsupportedOperationException("use read(byteArray)")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (!closed) {
            when (val action = readActions.poll(100, TimeUnit.MILLISECONDS) ?: continue) {
                is ReadAction.Bytes -> {
                    check(action.value.size <= length) {
                        "test input frame ${action.value.size} exceeds buffer length $length"
                    }
                    action.value.copyInto(buffer, destinationOffset = offset)
                    return action.value.size
                }

                is ReadAction.Error -> throw action.exception

                ReadAction.End -> {
                    closed = true
                    return -1
                }
            }
        }

        return -1
    }

    override fun close() {
        closed = true
        readActions.offer(ReadAction.End)
    }
}

private class RecordingOutputStream : ByteArrayOutputStream() {
    val writtenBytes: ByteArray
        get() = toByteArray()
}

private fun waitUntil(
    description: String,
    timeoutMillis: Long = 2_000L,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) {
            return
        }

        Thread.sleep(10L)
    }

    throw AssertionError("Timed out waiting for $description")
}
