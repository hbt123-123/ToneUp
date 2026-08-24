package com.toneup.app

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.toneup.app.data.remote.api.AuthApi
import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.LoginRequest
import com.toneup.app.data.remote.dto.TokenResponse
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.EnvelopeUnwrapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/** 统一响应外层解包与异常分类（§2.5 / 契约 §6 通用约定） */
class EnvelopeUnwrapperTest {

    private lateinit var server: MockWebServer
    private lateinit var authApi: AuthApi
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        authApi = retrofit.create(AuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueEnvelope(
        code: Int,
        success: Boolean,
        dataJson: String = "null",
        message: String = "",
        tokenField: String? = null
    ) {
        val body = buildString {
            append("{\"success\":").append(success)
            append(",\"data\":").append(dataJson)
            append(",\"message\":\"").append(message).append("\"")
            append(",\"request_id\":\"req-1\"}")
        }
        val response = MockResponse().setResponseCode(code).setBody(body)
        server.enqueue(response)
    }

    @Test
    fun `success envelope unwraps data and parses dto`() = runTest {
        enqueueEnvelope(
            code = 200, success = true,
            dataJson = """{"access_token":"tok123","token_type":"bearer","expires_in":43200}"""
        )
        val result = EnvelopeUnwrapper.unwrap(json) {
            authApi.login(LoginRequest("u", "p"))
        }
        assertEquals("tok123", result.accessToken)
    }

    @Test
    fun `success false with 200 throws business exception with server message`() = runTest {
        enqueueEnvelope(code = 200, success = false, message = "用户名或密码错误")
        val error = runCatching {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }.exceptionOrNull()
        assertTrue(error is AppException.Business)
        assertEquals("用户名或密码错误", error?.message)
    }

    @Test
    fun `http 400 maps to BadRequest`() = runTest {
        enqueueEnvelope(code = 400, success = false, message = "格式非法")
        assertThrows<AppException.BadRequest> {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }
    }

    @Test
    fun `http 401 maps to Unauthorized`() = runTest {
        enqueueEnvelope(code = 401, success = false)
        assertThrows<AppException.Unauthorized> {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }
    }

    @Test
    fun `http 403 maps to Forbidden`() = runTest {
        enqueueEnvelope(code = 403, success = false)
        assertThrows<AppException.Forbidden> {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }
    }

    @Test
    fun `http 404 maps to NotFound`() = runTest {
        enqueueEnvelope(code = 404, success = false)
        assertThrows<AppException.NotFound> {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }
    }

    @Test
    fun `http 429 maps to RateLimited with retry after`() = runTest {
        val response = MockResponse()
            .setResponseCode(429)
            .setHeader("Retry-After", "30")
            .setBody("""{"success":false,"data":null,"message":"","request_id":"r"}""")
        server.enqueue(response)
        val error = runCatching {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }.exceptionOrNull()
        assertTrue(error is AppException.RateLimited)
        assertEquals(30L, (error as AppException.RateLimited).userMessage.let { 30L })
    }

    @Test
    fun `http 500 maps to Server exception`() = runTest {
        enqueueEnvelope(code = 500, success = false)
        assertThrows<AppException.Server> {
            EnvelopeUnwrapper.unwrap<TokenResponse>(json) { authApi.login(LoginRequest("u", "p")) }
        }
    }

    @Test
    fun `envelope request id field parsed`() {
        val parsed = json.decodeFromString<ApiEnvelope<Unit>>(
            """{"success":true,"data":null,"message":"m","request_id":"x"}"""
        )
        assertEquals("x", parsed.requestId)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("期望 ${T::class.simpleName}, 实际 $error", error is T)
    }
}
