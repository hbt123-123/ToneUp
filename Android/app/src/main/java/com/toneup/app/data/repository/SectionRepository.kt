package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.SectionsApi
import com.toneup.app.data.remote.dto.SectionsResponse
import javax.inject.Inject
import javax.inject.Singleton

/** 分组列表数据仓库 */
@Singleton
class SectionRepository @Inject constructor(
    private val sectionsApi: SectionsApi,
    private val jsonProvider: JsonProvider
) {
    suspend fun sections(bankId: String): SectionsResponse =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sectionsApi.sections(bankId)
        }
}
