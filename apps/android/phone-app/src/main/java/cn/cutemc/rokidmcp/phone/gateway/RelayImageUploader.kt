package cn.cutemc.rokidmcp.phone.gateway

import cn.cutemc.rokidmcp.share.protocol.constants.LocalProtocolErrorCodes
import cn.cutemc.rokidmcp.share.protocol.constants.RelayProtocolConstants
import cn.cutemc.rokidmcp.share.protocol.relay.ErrorResponse
import cn.cutemc.rokidmcp.share.protocol.relay.ImageUploadResponse
import cn.cutemc.rokidmcp.share.protocol.relay.RelayProtocolJson
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

data class RelayImageUploadInput(
    val relayBaseUrl: String,
    val deviceId: String,
    val requestId: String,
    val imageId: String,
    val transferId: String,
    val uploadToken: String,
    val contentType: String,
    val bytes: ByteArray,
    val sha256: String? = null,
)

data class RelayHttpResponse(
    val code: Int,
    val body: String,
)

fun interface RelayHttpExecutor {
    suspend fun execute(request: Request): RelayHttpResponse
}

class OkHttpRelayHttpExecutor(
    private val client: OkHttpClient = OkHttpClient(),
) : RelayHttpExecutor {
    override suspend fun execute(request: Request): RelayHttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            RelayHttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }
}

class RelayImageUploader(
    private val httpExecutor: RelayHttpExecutor = OkHttpRelayHttpExecutor(),
    private val json: Json = RelayProtocolJson.default,
) {
    suspend fun upload(input: RelayImageUploadInput): ImageUploadResponse {
        val request = Request.Builder()
            .url(buildUploadUrl(input.relayBaseUrl, input.imageId, input.uploadToken))
            .header("X-Device-Id", input.deviceId)
            .header("X-Request-Id", input.requestId)
            .put(input.bytes.toRequestBody(input.contentType.toMediaType()))

        if (!input.sha256.isNullOrBlank()) {
            request.header("X-Upload-Checksum-Sha256", input.sha256)
        }

        val response = httpExecutor.execute(request.build())
        if (response.code !in 200..299) {
            throw parseFailure(response)
        }

        return try {
            json.decodeFromString(ImageUploadResponse.serializer(), response.body)
        } catch (error: SerializationException) {
            Timber.tag("relay-upload").e(error, "relay returned an invalid image upload response body")
            throw RelayImageUploadException(
                code = LocalProtocolErrorCodes.UPLOAD_FAILED,
                message = error.message ?: "relay returned an invalid image upload response",
                retryable = true,
                cause = error,
            )
        }
    }

    private fun parseFailure(response: RelayHttpResponse): RelayImageUploadException {
        val relayError = try {
            json.decodeFromString(ErrorResponse.serializer(), response.body)
        } catch (error: SerializationException) {
            Timber.tag("relay-upload").w(error, "failed to decode relay error response body with HTTP ${response.code}")
            null
        }

        return RelayImageUploadException(
            code = relayError?.error?.code ?: LocalProtocolErrorCodes.UPLOAD_FAILED,
            message = relayError?.error?.message ?: "relay image upload failed with HTTP ${response.code}",
            retryable = relayError?.error?.retryable ?: (response.code >= 500),
        )
    }

    private fun buildUploadUrl(baseUrl: String, imageId: String, uploadToken: String): String {
        val uri = URI(baseUrl)
        val normalizedPath = uri.path.orEmpty().trimEnd('/')
        val uploadPath = if (normalizedPath.isBlank()) {
            "/api/${RelayProtocolConstants.API_VERSION}/images/$imageId"
        } else {
            "$normalizedPath/api/${RelayProtocolConstants.API_VERSION}/images/$imageId"
        }
        val encodedToken = URLEncoder.encode(uploadToken, StandardCharsets.UTF_8)
        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            uploadPath,
            "uploadToken=$encodedToken",
            uri.fragment,
        ).toString()
    }
}

class RelayImageUploadException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
