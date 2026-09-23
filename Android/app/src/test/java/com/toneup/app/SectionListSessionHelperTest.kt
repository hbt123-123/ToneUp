package com.toneup.app

import com.toneup.app.data.remote.api.SessionApi
import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.CreateSessionResponseDto
import com.toneup.app.data.remote.dto.DraftRequest
import com.toneup.app.data.remote.dto.DraftResponseDto
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.data.remote.dto.SessionDetailDto
import com.toneup.app.data.remote.dto.SessionListItemDto
import com.toneup.app.data.remote.dto.SessionResultDto
import com.toneup.app.data.remote.dto.SubmitSessionRequest
import com.toneup.app.data.remote.dto.SubmitSessionResponseDto
import com.toneup.app.data.repository.JsonProvider
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.SessionRepository
import com.toneup.app.ui.navigation.SectionListSessionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 任务 8 QA：服务端会话创建成功注册预填会话（happy）；
 * 无网/异常创建失败 → 回退本地全量刷题并触发提示事件 serverBacked=false（failure）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionListSessionHelperTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var registry: PracticeSessionRegistry

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        registry = PracticeSessionRegistry()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSessionRepository(
        private val fail: Boolean
    ) : SessionRepository(
        sessionApi = ThrowingSessionApi(),
        jsonProvider = JsonProvider(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
    ) {
        val createCalls = AtomicInteger(0)

        override suspend fun createSession(
            bankId: String,
            collectionIds: List<Long>?,
            typeCodes: List<String>?,
            count: Int
        ): CreateSessionResponseDto {
            createCalls.incrementAndGet()
            if (fail) throw java.net.ConnectException("no network")
            return CreateSessionResponseDto(
                sessionId = 42L,
                bankId = bankId,
                title = "t",
                totalCount = 2,
                questions = listOf(
                    QuestionDto(bankId = bankId, questionId = 1L, typeCode = "SINGLE"),
                    QuestionDto(bankId = bankId, questionId = 2L, typeCode = "JUDGE")
                )
            )
        }
    }

    /** 七端点全部抛错的最小 Api 实现（Fake 只 override createSession，其余不触达） */
    private class ThrowingSessionApi : SessionApi {
        override suspend fun create(
            bankId: String, collectionIds: List<Long>?, typeCodes: List<String>?, count: Int
        ): ApiEnvelope<CreateSessionResponseDto> = throw UnsupportedOperationException()

        override suspend fun list(page: Int, pageSize: Int): ApiEnvelope<PageData<SessionListItemDto>> =
            throw UnsupportedOperationException()

        override suspend fun detail(sessionId: Long): ApiEnvelope<SessionDetailDto> =
            throw UnsupportedOperationException()

        override suspend fun updateDraft(sessionId: Long, body: DraftRequest): ApiEnvelope<DraftResponseDto> =
            throw UnsupportedOperationException()

        override suspend fun submit(sessionId: Long, body: SubmitSessionRequest): ApiEnvelope<SubmitSessionResponseDto> =
            throw UnsupportedOperationException()

        override suspend fun result(sessionId: Long): ApiEnvelope<SessionResultDto> =
            throw UnsupportedOperationException()

        override suspend fun delete(sessionId: Long): ApiEnvelope<Unit> =
            throw UnsupportedOperationException()
    }

    private fun createHelper(repo: FakeSessionRepository) =
        SectionListSessionHelper(registry = registry, sessionRepository = repo)

    @Test
    fun `server session created registers prefilled session`() = runTest {
        val repo = FakeSessionRepository(fail = false)
        val helper = createHelper(repo)

        var readyId: String? = null
        var serverBacked: Boolean? = null
        helper.createSectionSession(
            bankId = "bank-1", collectionIds = listOf(3L), year = 2024,
            typeCodeFilter = null, count = 2, title = "t"
        ) { sessionId, backed ->
            readyId = sessionId
            serverBacked = backed
        }
        advanceUntilIdle()

        assertEquals(true, serverBacked)
        assertEquals("srv_42", readyId)
        val session: PracticeSession? = registry.get(readyId!!)
        assertNotNull(session)
        assertEquals(42L, session!!.serverSessionId)
        assertEquals(2, session.fixedRefs!!.size)
        // 题目已预填，练习页无需分页/逐题补取
        assertEquals(2, session.questions.size)
        assertEquals(1L, session.questions[0].questionId)
        assertEquals("bank-1", session.questions[1].bankId)
    }

    @Test
    fun `server create failure falls back to local session and signals toast event`() = runTest {
        val repo = FakeSessionRepository(fail = true)
        val helper = createHelper(repo)

        var readyId: String? = null
        var serverBacked: Boolean? = null
        helper.createSectionSession(
            bankId = "bank-1", collectionIds = listOf(3L), year = 2024,
            typeCodeFilter = "SINGLE", count = 20, title = "t"
        ) { sessionId, backed ->
            readyId = sessionId
            serverBacked = backed
        }
        advanceUntilIdle()

        // 创建失败 → serverBacked=false（UI Toast 事件）且回退本地全量会话
        assertEquals(false, serverBacked)
        assertNotNull(readyId)
        assertTrue(readyId!!.startsWith("sec_"))
        val session: PracticeSession? = registry.get(readyId!!)
        assertNotNull(session)
        assertNull(session!!.serverSessionId)
        assertNull(session.fixedRefs)
        assertEquals(2024, session.year)
        assertEquals("SINGLE", session.typeCodeFilter)
        assertEquals(1, repo.createCalls.get())
    }
}
