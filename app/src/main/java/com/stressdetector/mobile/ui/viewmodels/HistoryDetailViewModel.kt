package com.stressdetector.mobile.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportDao
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.domain.history.HistoryChartArtifact
import com.stressdetector.mobile.domain.history.HistoryChartArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryDetailUiState(
    val sessionId: String? = null,
    val isLoading: Boolean = false,
    val session: StressSessionEntity? = null,
    val report: StressSessionReportEntity? = null,
    val windows: List<StressWindowEvaluationEntity> = emptyList(),
    val chartArtifact: HistoryChartArtifact? = null,
    val errorMessage: String? = null
)

class HistoryDetailViewModel(
    application: Application,
    private val sessionDao: com.stressdetector.mobile.data.db.StressSessionDao,
    private val reportDao: StressSessionReportDao,
    private val chartStore: HistoryChartArtifactStore
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HistoryDetailUiState())
    val uiState: StateFlow<HistoryDetailUiState> = mutableState.asStateFlow()

    fun open(sessionId: String) {
        if (sessionId.isBlank()) {
            mutableState.value = HistoryDetailUiState(errorMessage = "ID sesi tidak tersedia")
            return
        }
        if (mutableState.value.sessionId == sessionId && mutableState.value.session != null) return

        mutableState.value = HistoryDetailUiState(sessionId = sessionId, isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val session = sessionDao.getById(sessionId)
                    ?: error("Sesi tidak ditemukan")
                HistoryDetailUiState(
                    sessionId = sessionId,
                    session = session,
                    report = reportDao.getSessionReport(sessionId),
                    windows = reportDao.getWindowEvaluations(sessionId),
                    chartArtifact = chartStore.load(sessionId)
                )
            }.onSuccess { mutableState.value = it }
                .onFailure { error ->
                    mutableState.value = HistoryDetailUiState(
                        sessionId = sessionId,
                        errorMessage = error.message ?: "Report sesi tidak dapat dibaca"
                    )
                }
        }
    }

    fun retry() {
        uiState.value.sessionId?.let(::open)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val database = StressDatabase.get(application)
                return HistoryDetailViewModel(
                    application = application,
                    sessionDao = database.stressSessionDao(),
                    reportDao = database.stressSessionReportDao(),
                    chartStore = HistoryChartArtifactStore(application)
                ) as T
            }
        }
    }
}
