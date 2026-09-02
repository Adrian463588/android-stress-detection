package com.stressdetector.mobile.domain

import com.stressdetector.inference.ModelSessionContract
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.shared.PpgType

/**
 * Abstraction for stress inference, adhering to Dependency Inversion Principle (DIP).
 */
interface IStressInferenceUseCase : AutoCloseable {
    fun process(window: WindowSnapshot): PpgProcessingSnapshot
    fun processPrepared(window: PreparedPpgWindow): PpgProcessingSnapshot
    fun prepareSession(window: WindowSnapshot): PpgPreprocessResult
    fun preview(window: WindowSnapshot): PpgChartData
    fun chartForPrepared(window: PreparedPpgWindow): PpgChartData
    fun infer(window: WindowSnapshot): PredictionAvailability
    fun requireConfigured(types: Set<PpgType>)
    fun modelContract(type: PpgType): ModelSessionContract? = null
}
