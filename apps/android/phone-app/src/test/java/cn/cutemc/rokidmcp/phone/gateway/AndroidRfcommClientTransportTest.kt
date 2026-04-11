package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber

class AndroidRfcommClientTransportTest {
    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start send and remote close emit masked lifecycle traces`() = runTest test@{
        val dispatcher = StandardTestDispatcher(testScheduler)
        val socket = FakeClientSocket(readBytes = byteArrayOf(0x01, 0x02, 0x03))
        val transport = AndroidRfcommClientTransport(
            adapterAvailabilityProvider = BluetoothAdapterAvailabilityProvider { true },
            socketFactory = RfcommClientSocketFactory { targetDeviceAddress, _ ->
                assertTrue(targetDeviceAddress == FULL_ADDRESS)
                socket
            },
            ioDispatcher = dispatcher,
            transportScope = backgroundScope,
        )

        val logs = captureTimberLogs {
            backgroundScope.launch {
                transport.start(FULL_ADDRESS)
                transport.send(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            }

            this@test.runCurrent()
            this@test.runCurrent()
        }

        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "bluetooth adapter available")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "looking up target device address=$MASKED_ADDRESS")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "transport state -> CONNECTING")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "socket connect starting target=$MASKED_ADDRESS")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "socket connect succeeded target=$MASKED_ADDRESS")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "transport state -> CONNECTED")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "read loop started target=$MASKED_ADDRESS")
        logs.assertLog(Log.VERBOSE, RFCOMM_LOG_TAG, "wrote 4 RFCOMM bytes")
        logs.assertLog(Log.VERBOSE, RFCOMM_LOG_TAG, "read 3 RFCOMM bytes")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "remote closed RFCOMM stream target=$MASKED_ADDRESS")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "cleaning up RFCOMM resources reason=rfcomm client stream ended")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "transport state -> DISCONNECTED")
        logs.assertNoSensitiveData()
        assertTrue(logs.all { entry -> FULL_ADDRESS !in entry.message })
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), socket.writtenBytes.toByteArray())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect failure emits masked error traces`() = runTest test@{
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connectFailure = IOException("connect boom")
        val transport = AndroidRfcommClientTransport(
            adapterAvailabilityProvider = BluetoothAdapterAvailabilityProvider { true },
            socketFactory = RfcommClientSocketFactory { _, _ ->
                FakeClientSocket(connectError = connectFailure)
            },
            ioDispatcher = dispatcher,
            transportScope = backgroundScope,
        )

        val logs = captureTimberLogs {
            backgroundScope.launch {
                try {
                    transport.start(FULL_ADDRESS)
                    fail("start should throw when socket connect fails")
                } catch (expected: IOException) {
                    assertTrue(expected === connectFailure)
                }
            }

            this@test.runCurrent()
        }

        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "bluetooth adapter available")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "looking up target device address=$MASKED_ADDRESS")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "transport state -> CONNECTING")
        logs.assertLog(Log.INFO, RFCOMM_LOG_TAG, "socket connect starting target=$MASKED_ADDRESS")
        logs.assertLog(Log.ERROR, RFCOMM_LOG_TAG, "socket connect failed target=$MASKED_ADDRESS")
        logs.assertLog(Log.ERROR, RFCOMM_LOG_TAG, "transport state -> ERROR")
        logs.assertNoSensitiveData()
        assertTrue(logs.all { entry -> FULL_ADDRESS !in entry.message })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `manual stop ignores socket close read failure`() = runTest {
        val blockingInputStream = BlockingCloseAwareInputStream()
        val socket = FakeClientSocket(inputStream = blockingInputStream)
        val transport = AndroidRfcommClientTransport(
            adapterAvailabilityProvider = BluetoothAdapterAvailabilityProvider { true },
            socketFactory = RfcommClientSocketFactory { _, _ -> socket },
            transportScope = backgroundScope,
        )
        val events = mutableListOf<PhoneTransportEvent>()
        val collector = backgroundScope.launch {
            transport.events.collect(events::add)
        }

        val startJob = async {
            transport.start(FULL_ADDRESS)
        }
        startJob.await()
        blockingInputStream.awaitReadStarted()

        transport.stop("manual")

        withTimeout(5_000L) {
            while (events.none { it is PhoneTransportEvent.ConnectionClosed && it.reason == "manual" }) {
                delay(10)
            }
        }

        assertTrue(events.none { it is PhoneTransportEvent.Failure })
        assertTrue(transport.state.value == PhoneTransportState.DISCONNECTED)
        collector.cancel()
    }

    private class FakeClientSocket(
        readBytes: ByteArray = byteArrayOf(),
        override val inputStream: InputStream = ByteArrayInputStream(readBytes),
        override val outputStream: OutputStream = ByteArrayOutputStream(),
        private val connectError: IOException? = null,
    ) : RfcommClientSocket {
        val writtenBytes: ByteArrayOutputStream
            get() = outputStream as ByteArrayOutputStream

        override fun connect() {
            connectError?.let { throw it }
        }

        override fun close() {
            inputStream.close()
            outputStream.close()
        }
    }

    private class BlockingCloseAwareInputStream : InputStream() {
        private val readStarted = CountDownLatch(1)
        private val unblockRead = CountDownLatch(1)
        @Volatile
        private var closed = false

        override fun read(): Int {
            throw UnsupportedOperationException("single-byte reads are not used in this test")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readStarted.countDown()
            unblockRead.await(5, TimeUnit.SECONDS)
            if (closed) {
                throw IOException("bt socket closed, read return: -1")
            }
            return -1
        }

        override fun close() {
            closed = true
            unblockRead.countDown()
        }

        fun awaitReadStarted() {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val RFCOMM_LOG_TAG = "rfcomm-client"
        const val FULL_ADDRESS = "00:11:22:33:44:55"
        const val MASKED_ADDRESS = "**:**:**:**:44:55"
    }
}
