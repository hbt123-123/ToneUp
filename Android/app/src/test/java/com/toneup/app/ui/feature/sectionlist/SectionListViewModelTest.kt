package com.toneup.app.ui.feature.sectionlist

import androidx.lifecycle.SavedStateHandle
import com.toneup.app.data.remote.dto.SectionItem
import com.toneup.app.data.remote.dto.SectionsResponse
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.SectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SectionListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeSectionRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSectionRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(bankId: String = "bank-1"): SectionListViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("bankId" to bankId))
        return SectionListViewModel(
            savedStateHandle = savedStateHandle,
            sectionRepository = fakeRepository
        )
    }

    @Test
    fun `init loads sections and populates state`() = runTest {
        fakeRepository.sectionsResponse = SectionsResponse(
            bankId = "bank-1",
            category = "数学一",
            sections = listOf(
                SectionItem(year = 2024, total = 20, done = 5),
                SectionItem(title = "极限专题", total = 15, done = 0)
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("bank-1", state.bankId)
        assertEquals("数学一", state.category)
        assertEquals(2, state.sections.size)
    }

    @Test
    fun `network error shows error message`() = runTest {
        fakeRepository.error = AppException.Network(java.net.ConnectException("no network"))

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals("网络不可用，请检查网络后重试", state.error)
    }

    @Test
    fun `business error shows user message`() = runTest {
        fakeRepository.error = AppException.Business("题库不存在")

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertEquals("题库不存在", state.error)
    }

    @Test
    fun `selectTab filters sections correctly`() = runTest {
        fakeRepository.sectionsResponse = SectionsResponse(
            bankId = "bank-1",
            sections = listOf(
                SectionItem(year = 2024, total = 20),
                SectionItem(year = 2023, total = 18),
                SectionItem(title = "极限专题", total = 10)
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // 默认全部 tab
        assertEquals(3, vm.state.value.filteredSections.size)

        // 切换到真题 tab
        vm.selectTab(SectionTab.REAL_EXAM)
        assertEquals(2, vm.state.value.filteredSections.size)
        assertTrue(vm.state.value.filteredSections.all { it.year != null })

        // 切换到专题 tab
        vm.selectTab(SectionTab.TOPIC)
        assertEquals(1, vm.state.value.filteredSections.size)
        assertTrue(vm.state.value.filteredSections.all { it.year == null })
    }

    @Test
    fun `selectFilter filters sections correctly`() = runTest {
        fakeRepository.sectionsResponse = SectionsResponse(
            bankId = "bank-1",
            sections = listOf(
                SectionItem(title = "A", total = 10, done = 0),
                SectionItem(title = "B", total = 10, done = 10, wrong = 3),
                SectionItem(title = "C", total = 10, done = 5, favorited = true),
                SectionItem(title = "D", total = 10, done = 5)
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // 全部
        assertEquals(4, vm.state.value.filteredSections.size)

        // 未做
        vm.selectFilter(FilterTab.UNDONE)
        assertEquals(1, vm.state.value.filteredSections.size)
        assertEquals("A", vm.state.value.filteredSections.first().title)

        // 错题
        vm.selectFilter(FilterTab.WRONG)
        assertEquals(1, vm.state.value.filteredSections.size)
        assertEquals("B", vm.state.value.filteredSections.first().title)

        // 收藏
        vm.selectFilter(FilterTab.FAVORITE)
        assertEquals(1, vm.state.value.filteredSections.size)
        assertEquals("C", vm.state.value.filteredSections.first().title)
    }

    @Test
    fun `retry reloads sections`() = runTest {
        fakeRepository.error = AppException.Network(java.net.ConnectException("no network"))

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals("网络不可用，请检查网络后重试", vm.state.value.error)

        // 网络恢复
        fakeRepository.error = null
        fakeRepository.sectionsResponse = SectionsResponse(
            bankId = "bank-1",
            sections = listOf(SectionItem(title = "A", total = 10))
        )

        vm.retry()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.sections.size)
    }
}

// ---- Fake implementations for testing ----

private class FakeSectionRepository : SectionRepository(
    sectionsApi = object : com.toneup.app.data.remote.api.SectionsApi {
        override suspend fun sections(bankId: String): com.toneup.app.data.remote.dto.ApiEnvelope<SectionsResponse> =
            throw UnsupportedOperationException()
    },
    jsonProvider = com.toneup.app.data.repository.JsonProvider(
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    )
) {
    var sectionsResponse: SectionsResponse? = null
    var error: Exception? = null

    override suspend fun sections(bankId: String): SectionsResponse {
        error?.let { throw it }
        return sectionsResponse ?: SectionsResponse(bankId = bankId)
    }
}
