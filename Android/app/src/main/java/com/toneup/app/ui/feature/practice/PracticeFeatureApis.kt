package com.toneup.app.ui.feature.practice

import androidx.lifecycle.ViewModel
import com.toneup.app.data.remote.api.FeedbackApi
import com.toneup.app.data.remote.api.NotesSharedApi
import com.toneup.app.data.remote.api.WrongQuestionApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PracticeFeatureApis @Inject constructor(
    val feedbackApi: FeedbackApi,
    val notesSharedApi: NotesSharedApi,
    val wrongQuestionApi: WrongQuestionApi
) : ViewModel()
