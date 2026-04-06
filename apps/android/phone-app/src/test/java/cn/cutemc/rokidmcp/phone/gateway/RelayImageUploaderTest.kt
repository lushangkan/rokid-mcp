package cn.cutemc.rokidmcp.phone.gateway

import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        val response = uploader.upload(
            RelayImageUploadInput(
                relayBaseUrl = "https://relay.example.com/base",
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

        val request = requireNotNull(capturedRequest)
        val bodyBuffer = Buffer()
        request.body?.writeTo(bodyBuffer)

        assertEquals("img_test_1", response.image.imageId)
        assertEquals("phone-device", request.header("X-Device-Id"))
        assertEquals("req_capture_1", request.header("X-Request-Id"))
        assertEquals("abcd", request.header("X-Upload-Checksum-Sha256"))
        assertEquals(
            "https://relay.example.com/base/api/v1/images/img_test_1?uploadToken=upl_test_1",
            request.url.toString(),
        )
        assertArrayEquals(imageBytes, bodyBuffer.readByteArray())
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

        val error = assertThrows(RelayImageUploadException::class.java) {
            kotlinx.coroutines.runBlocking {
                uploader.upload(
                    RelayImageUploadInput(
                        relayBaseUrl = "https://relay.example.com",
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

        assertEquals("IMAGE_TOO_LARGE", error.code)
        assertEquals("too large", error.message)
        assertNotNull(error)
    }
}
