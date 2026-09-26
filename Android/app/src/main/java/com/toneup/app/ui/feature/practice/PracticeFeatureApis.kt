package com.toneup.app.ui.feature.practice

import androidx.lifecycle.ViewModel
import com.toneup.app.data.remote.api.FeedbackApi
import com.toneup.app.data.remote.api.NotesSharedApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// TODO(M-184)：本类仅为向组合层透传 @Singleton Retrofit 接口的多余包装，
//  后续应让 FeedbackForm/NotesSharePanel 等直接注入所需 Api（或下沉到各自 ViewModel），
//  并移除 PracticeScreen 的 featureApis 参数链，当前仅保守瘦身不做结构拆分。
@HiltViewModel
class PracticeFeatureApis @Inject constructor(
    val feedbackApi: FeedbackApi,
    val notesSharedApi: NotesSharedApi
) : ViewModel()
