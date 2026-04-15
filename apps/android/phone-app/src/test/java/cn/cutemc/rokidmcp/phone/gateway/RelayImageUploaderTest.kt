package cn.cutemc.rokidmcp.phone.gateway

import android.util.Log
import cn.cutemc.rokidmcp.phone.logging.assertLog
import cn.cutemc.rokidmcp.phone.logging.assertNoSensitiveData
import cn.cutemc.rokidmcp.phone.logging.captureTimberLogs
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayImageUploaderTest {
    @Test
    fun `uploads image bytes with relay headers`() = runTest {
        val imageBytes = "jpeg".encodeToByteArray()
        var capturedRequest: okhttp3.Request? = null
        val uploader = RelayImageUploader(
            httpExecutor = RelayHttpExecutor { request ->
                capturedRequest = request
                RelayHttpResponse(
                    code = 200,
                    body = """
                        {
                          "ok":true,
                          "image":{
                            "imageId":"img_test_1",
                            "transferId":"trf_test_1",
                            "status":"UPLOADED",
                            "mimeType":"image/jpeg",
                            "size":4,
                            "sha256":"abcd",
                            "uploadedAt":1717172200
                          },
                          "timestamp":1717172201
                        }
                    """.trimIndent(),
                )
            },
        )

        lateinit var response: cn.cutemc.rokidmcp.share.protocol.relay.ImageUploadResponse
        val logs = captureTimberLogs {
            kotlinx.coroutines.runBlocking {
                response = uploader.upload(
                    RelayImageUploadInput(
                        relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base",
                        deviceId = "phone-device",
                        requestId = "req_capture_1",
                        imageId = "img_test_1",
                        transferId = "trf_test_1",
                        uploadToken = "upl_test_1",
                        contentType = "image/jpeg",
                        bytes = imageBytes,
                        sha256 = "abcd",
                    ),
                )
            }
        }

        val request = requireNotNull(capturedRequest)
        val bodyBuffer = Buffer()
        request.body?.writeTo(bodyBuffer)

        assertEquals("img_test_1", response.image.imageId)
        assertEquals("phone-device", request.header("X-Device-Id"))
        assertEquals("req_capture_1", request.header("X-Request-Id"))
        assertEquals("abcd", request.header("X-Upload-Checksum-Sha256"))
        assertNull(request.header("Authorization"))
        assertEquals(setOf("uploadToken"), request.url.queryParameterNames)
        assertEquals(
            "https://relay-user:relay-pass@relay.example.com/base/api/v1/images/img_test_1?uploadToken=upl_test_1",
            request.url.toString(),
        )
        assertArrayEquals(imageBytes, bodyBuffer.readByteArray())
        logs.assertLog(Log.INFO, "relay-upload", "starting relay image upload requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1 url=https://relay.example.com/base/api/v1/images/img_test_1")
        logs.assertLog(Log.INFO, "relay-upload", "relay image upload succeeded requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1 status=UPLOADED url=https://relay.example.com/base/api/v1/images/img_test_1")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("relay-user") || it.message.contains("upl_test_1") })
    }

    @Test
    fun `executor failures redact auth secrets from upload logs`() = runTest {
        val uploader = RelayImageUploader(
            httpExecutor = RelayHttpExecutor {
                throw IllegalStateException("relay auth failed authToken=auth-from-config Bearer bearer-secret uploadToken=upl_test_1")
            },
        )

        lateinit var error: IllegalStateException
        val logs = captureTimberLogs {
            error = assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    uploader.upload(
                        RelayImageUploadInput(
                            relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base",
                            deviceId = "phone-device",
                            requestId = "req_capture_1",
                            imageId = "img_test_1",
                            transferId = "trf_test_1",
                            uploadToken = "upl_test_1",
                            contentType = "image/jpeg",
                            bytes = byteArrayOf(1, 2, 3),
                        ),
                    )
                }
            }
        }

        assertEquals("relay auth failed authToken=auth-from-config Bearer bearer-secret uploadToken=upl_test_1", error.message)
        logs.assertLog(Log.ERROR, "relay-upload", "relay image upload failed requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1 url=https://relay.example.com/base/api/v1/images/img_test_1")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { entry ->
            entry.message.contains("auth-from-config") ||
                entry.message.contains("bearer-secret") ||
                entry.message.contains("upl_test_1") ||
                entry.throwable?.message.orEmpty().contains("auth-from-config") ||
                entry.throwable?.message.orEmpty().contains("bearer-secret") ||
                entry.throwable?.message.orEmpty().contains("upl_test_1")
        })
    }

    @Test
    fun `maps relay error body into upload exception`() = runTest {
        val uploader = RelayImageUploader(
            httpExecutor = RelayHttpExecutor {
                RelayHttpResponse(
                    code = 413,
                    body = """
                        {
                          "ok":false,
                          "error":{
                            "code":"IMAGE_TOO_LARGE",
                            "message":"too large",
                            "retryable":false,
                            "details":null
                          },
                          "timestamp":1717172201
                        }
                    """.trimIndent(),
                )
            },
        )

        lateinit var error: RelayImageUploadException
        val logs = captureTimberLogs {
            error = assertThrows(RelayImageUploadException::class.java) {
                kotlinx.coroutines.runBlocking {
                    uploader.upload(
                        RelayImageUploadInput(
                            relayBaseUrl = "https://relay-user:relay-pass@relay.example.com/base",
                            deviceId = "phone-device",
                            requestId = "req_capture_1",
                            imageId = "img_test_1",
                            transferId = "trf_test_1",
                            uploadToken = "upl_test_1",
                            contentType = "image/jpeg",
                            bytes = byteArrayOf(1, 2, 3),
                        ),
                    )
                }
            }
        }

        assertEquals("IMAGE_TOO_LARGE", error.code)
        assertEquals("too large", error.message)
        assertNotNull(error)
        logs.assertLog(Log.INFO, "relay-upload", "starting relay image upload requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1 url=https://relay.example.com/base/api/v1/images/img_test_1")
        logs.assertLog(Log.ERROR, "relay-upload", "relay image upload failed requestId=req_capture_1 transferId=trf_test_1 imageId=img_test_1 httpCode=413 code=IMAGE_TOO_LARGE retryable=false url=https://relay.example.com/base/api/v1/images/img_test_1")
        logs.assertNoSensitiveData()
        assertFalse(logs.any { it.message.contains("relay-user") || it.message.contains("upl_test_1") })
    }
}
