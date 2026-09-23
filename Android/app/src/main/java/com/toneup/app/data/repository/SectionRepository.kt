package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.SectionsApi
import com.toneup.app.data.remote.dto.SectionsResponse
import javax.inject.Inject
import javax.inject.Singleton

/** 分组列表数据仓库（open 仅为测试 Fake 继承） */
@Singleton
open class SectionRepository @Inject constructor(
    private val sectionsApi: SectionsApi,
    private val jsonProvider: JsonProvider
) {
    open suspend fun sections(bankId: String): SectionsResponse =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sectionsApi.sections(bankId)
        }
}
