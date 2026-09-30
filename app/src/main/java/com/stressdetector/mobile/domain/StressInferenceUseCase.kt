package com.stressdetector.mobile.domain

import android.content.Context
import com.stressdetector.inference.ModelContractStatus
import com.stressdetector.inference.ModelSessionContract
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.ml.StressInferenceRuntime
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.SignalQualityGate
import com.stressdetector.shared.PpgType

class StressInferenceUseCase(private val runtime: StressInferenceRuntime, context: Context, private val ownsRuntime: Boolean = false) :
    IStressInferenceUseCase {
    private val qualityGate = SignalQualityGate(
        com.stressdetector.mobile.signal.AssetLiveMonitorRuntimeContract(context).loadConfig()
    )
    private val coordinator = HomePpgProcessingCoordinator(runtime, qualityGate)

    constructor(context: Context) : this(
        runtime = StressInferenceRuntime(context),
        context = context,
        ownsRuntime = true
    )

    override fun infer(window: WindowSnapshot): PredictionAvailability = process(window).availability

    override fun requireConfigured(types: Set<PpgType>) {
        runtime.requireConfigured(types)
    }

    override fun modelContract(type: PpgType): ModelSessionContract? = runtime.modelContract(type)

    override fun modelContractStatus(type: PpgType): ModelContractStatus = runtime.modelContractStatus(type)

    override fun process(window: WindowSnapshot): PpgProcessingSnapshot = coordinator.process(window)

    override fun processPrepared(window: PreparedPpgWindow): PpgProcessingSnapshot = coordinator.processPrepared(window)

    override fun prepareSession(window: WindowSnapshot): PpgPreprocessResult = runtime.prepareSession(window)

    override fun preview(window: WindowSnapshot): PpgChartData = coordinator.preview(window)

    override fun chartForPrepared(window: PreparedPpgWindow): PpgChartData = coordinator.chartForPrepared(window)

    override fun close() {
        if (ownsRuntime) runtime.close()
    }
}
