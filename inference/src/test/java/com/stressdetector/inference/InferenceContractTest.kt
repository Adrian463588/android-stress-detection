package com.stressdetector.inference

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceContractTest {
    @Test
    fun canonicalOnnxOutputsMapScalarAndStressClassWithoutInversion() {
        assertEquals(0.2f, OnnxOutputParser.extractStressProbability(floatArrayOf(0.2f)))
        assertEquals(0.8f, OnnxOutputParser.extractStressProbability(floatArrayOf(0.2f, 0.8f)))
        assertEquals(
            0.8f,
            OnnxOutputParser.extractStressProbability(arrayOf(floatArrayOf(0.2f, 0.8f)))
        )
        assertEquals(
            0.35f,
            OnnxOutputParser.extractStressProbability(arrayOf(floatArrayOf(0.35f)))
        )
        assertEquals(0.35f, OnnxOutputParser.extractStressProbability(FloatBuffer.wrap(floatArrayOf(0.35f))))
    }

    @Test
    fun poorQualityNeverReturnsPrediction() {
        val result = StressInferenceEngine(null).inferFeatures(floatArrayOf(), "poor")
        assertEquals(InferenceStatus.INVALID_QUALITY, result.status)
        assertNull(result.label)
        assertNull(result.stressProbability)
    }

    @Test
    fun brokenTimestampIsRejectedWithoutProbability() {
        val window =
            RawPpgWindow(
                sensorChannel = "ppg_ir",
                samplingRateHz = 100.0,
                timestampDeltasMs = doubleArrayOf(0.0, -1.0),
                rawValues = doubleArrayOf(1.0, 2.0)
            )
        val result = StressInferenceEngine(null).validateRawWindow(window)
        assertEquals(InferenceStatus.INVALID_TIMESTAMP, result?.status)
        assertNull(result?.stressProbability)
    }

    @Test
    fun sessionInitializationFailureIsNotReportedAsGenericModelUnavailable() {
        val engine =
            StressInferenceEngine(validResources()) {
                error("simulated native session failure")
            }

        val result = engine.inferFeatures(FloatArray(34) { 0.5f }, "good")

        assertTrue(engine.readiness is StressModelReadiness.RuntimeUnavailable)
        assertEquals(InferenceStatus.RUNTIME_UNAVAILABLE, result.status)
        assertEquals("onnx_session_init_failed", result.rejectionReason)
    }

    @Test
    fun preflightParityFailureBlocksPrediction() {
        val engine =
            StressInferenceEngine(validResources()) {
                object : StressClassifier {
                    override fun predict(values: FloatArray): Float = 0.75f

                    override fun close() = Unit
                }
            }

        val result = engine.inferFeatures(FloatArray(34) { 0.5f }, "good")

        assertTrue(engine.readiness is StressModelReadiness.PreflightFailed)
        assertEquals(InferenceStatus.PREFLIGHT_FAILED, result.status)
        assertEquals("onnx_preflight_parity_failed", result.rejectionReason)
    }

    @Test
    fun everyPackagedPreflightVectorIsExecuted() {
        val resources =
            validResources().copy(
                preflightVectors =
                List(100) {
                    ModelPreflightVector(FloatArray(34) { it.toFloat() }, 0.25f)
                }
            )
        val engine =
            StressInferenceEngine(resources) {
                object : StressClassifier {
                    override fun predict(values: FloatArray): Float = 0.25f

                    override fun close() = Unit
                }
            }

        assertTrue(engine.readiness is StressModelReadiness.Ready)
        assertEquals(100, engine.preflightReceipt?.vectorCount)
        assertEquals(0f, engine.preflightReceipt?.maxAbsDiff)
    }

    @Test
    fun validInferenceCarriesTheManifestDecisionPolicy() {
        val engine =
            StressInferenceEngine(validResources()) {
                object : StressClassifier {
                    override fun predict(values: FloatArray): Float = 0.25f

                    override fun close() = Unit
                }
            }

        val result = engine.inferFeatures(FloatArray(34) { 0.5f }, "good")

        assertEquals(InferenceStatus.VALID, result.status)
        assertEquals("test-model", result.decisionPolicy?.modelVersion)
        assertEquals(0.5f, result.decisionPolicy?.probabilityThreshold)
        assertEquals("test-calibration", result.decisionPolicy?.calibrationVersion)
        assertEquals("b".repeat(64), result.trace?.manifestFingerprint)
        assertEquals("ppg_green", result.trace?.sensorChannel)
        assertEquals(34, result.trace?.featureCount)
        assertEquals(34, result.trace?.sampleCount)
        assertEquals(listOf("relax", "task"), result.trace?.labelOrder)
        assertEquals("RELAX", result.trace?.finalLabel)
    }

    @Test
    fun rejectionVectorsAreValidatedButNotExecutedAsOnnxPreflight() {
        val input = List(34) { "0.5" }.joinToString(",")
        val valid = "{\"kind\":\"valid_raw_window\",\"input\":[$input],\"onnx_probability\":0.25}"
        val rejection = "{\"kind\":\"quality_rejection\",\"expected_status\":\"invalid_quality\",\"expected_probability\":null}"
        val json = "{\"cases\":[${List(99) { valid }.plus(rejection).joinToString(",")}] }"

        val preflight = parsePreflightVectors(json.toByteArray(), 34)

        assertEquals(99, preflight.size)
        assertEquals(34, preflight.first().input.size)
        assertEquals(0.25f, preflight.first().expectedProbability)
    }

    @Test
    fun rejectionVectorWithProbabilityIsRejected() {
        val input = List(34) { "0.5" }.joinToString(",")
        val valid = "{\"kind\":\"valid_raw_window\",\"input\":[$input],\"onnx_probability\":0.25}"
        val invalid = "{\"kind\":\"quality_rejection\",\"expected_status\":\"invalid_quality\",\"onnx_probability\":0.25}"
        val json = "{\"cases\":[${List(99) { valid }.plus(invalid).joinToString(",")}] }"

        assertThrows(IllegalArgumentException::class.java) {
            parsePreflightVectors(json.toByteArray(), 34)
        }
    }

    @Test
    fun manifestRejectsNonCanonicalInputShape() {
        val manifest =
            validResources().manifest.copy(
                inputs = listOf(TensorContract("float_input", "float32", listOf(1, 33)))
            )

        assertThrows(IllegalArgumentException::class.java) { manifest.requireValid() }
    }

    @Test
    fun manifestExposesVersionedSessionContract() {
        val contract = validResources().manifest.sessionContract()
        val decisionPolicy = validResources().manifest.decisionPolicy()

        assertEquals("test-model", contract.modelVersion)
        assertEquals("ppg_green", contract.sensorChannel)
        assertEquals(240, contract.analysisSeconds)
        assertEquals(180, contract.windowSeconds)
        assertEquals(10, contract.strideSeconds)
        assertEquals(7, contract.expectedWindowsPerSession)
        assertEquals(34, contract.featureOrder.size)
        assertEquals(25.0, contract.sourceRateHz, 0.0)
        assertEquals(25.0, contract.modelRateHz, 0.0)
        assertEquals("v3.0.0", contract.preprocessingVersion)
        assertEquals(listOf("relax", "task"), decisionPolicy.labelOrder)
        assertEquals(0.5f, decisionPolicy.probabilityThreshold)
        assertEquals("test-calibration", decisionPolicy.calibrationVersion)
        assertTrue(
            validResources().manifest.copy(cohort_access_count = 0).sessionContractStatus() is
                ModelContractStatus.Unavailable
        )
    }

    @Test
    fun validInferenceTraceCarriesAcquisitionAndFeatureProvenance() {
        val engine = StressInferenceEngine(validResources()) {
            object : StressClassifier {
                override fun predict(values: FloatArray): Float = 0.25f
                override fun close() = Unit
            }
        }

        val result = engine.inferFeatures(
            features = FloatArray(34) { 0.5f },
            qualityTier = "good",
            sampleCount = 4_500,
            traceContext = InferenceTraceContext(
                sessionId = "session-1",
                windowId = "ppg_green:30000-210000",
                trackerContract = "PDSATU_LITERAL_GREEN",
                acceptedSampleCount = 4_500,
                duplicateSampleCount = 2,
                observedCadenceHz = 24.9167,
                maxGapMillis = 42L,
                preprocessingVersion = "v3.0.0",
                featureVersion = "v4.0.0"
            )
        )

        assertEquals(InferenceStatus.VALID, result.status)
        assertEquals("session-1", result.trace?.sessionId)
        assertEquals("PDSATU_LITERAL_GREEN", result.trace?.trackerContract)
        assertEquals(4_500, result.trace?.acceptedSampleCount)
        assertEquals(2, result.trace?.duplicateSampleCount)
        assertEquals("v4.0.0", result.trace?.featureVersion)
        assertEquals(64, result.trace?.featureChecksum?.length)
    }

    @Test
    fun manifestRejectsMissingPhysicalParityReceipt() {
        val manifest = validResources().manifest.copy(device_parity_receipt_sha256 = "")

        assertThrows(IllegalArgumentException::class.java) { manifest.requireValid() }
    }

    @Test
    fun researchTierCandidateAcceptsTheMinimumPolicyWithoutPreferredMetrics() {
        val manifest =
            validResources().manifest.copy(
                release_status = "release_candidate",
                android_ready = false,
                device_parity_receipt_sha256 = "",
                balanced_accuracy_gate = 0.70,
                balanced_accuracy_holdout = 0.70,
                f1_gate = 0.70,
                f1_holdout = 0.70,
                roc_auc_gate = 0.70,
                roc_auc_holdout = 0.70,
                performance_tier = "research_usable",
                deployment_scope = "research",
                research_disclaimer = "Model penelitian — validasi internal terbatas. Bukan diagnosis klinis.",
                disclaimer_required = true
            )

        manifest.requireCandidateValid()
    }

    @Test
    fun candidateManifestWithoutReceiptDeserializesAsPendingInsteadOfCrashing() {
        val candidateJson =
            Gson().toJson(
                validResources().manifest.copy(
                    model_family = "logistic_regression",
                    release_status = "release_candidate",
                    android_ready = false,
                    device_parity_receipt_sha256 = null,
                    balanced_accuracy_development = 0.0,
                    f1_development = 0.0,
                    roc_auc_development = 0.0,
                    performance_tier = "research_usable",
                    deployment_scope = "research",
                    research_disclaimer = "Model penelitian — validasi internal terbatas. Bukan diagnosis klinis.",
                    disclaimer_required = true
                )
            )

        val parsed = Gson().fromJson(candidateJson, ModelManifest::class.java)

        parsed.requireCandidateValid()
        assertNull(parsed.device_parity_receipt_sha256)
    }

    @Test
    fun researchManifestRequiresDevelopmentOnlyEvidenceAndNoHoldoutReceipt() {
        val manifest =
            validResources().manifest.copy(
                schema_version = "onnx-research-v1",
                artifact_status = "research_candidate",
                evaluation_scope = "development_only",
                release_status = "blocked_existing_holdout_contract_mismatch",
                gate_passed = true,
                research_gate_passed = true,
                balanced_accuracy_development = 0.80,
                f1_development = 0.80,
                roc_auc_development = 0.84,
                performance_tier = "preferred",
                deployment_scope = "research",
                research_disclaimer = "Model penelitian — development only. Bukan diagnosis klinis.",
                disclaimer_required = true,
                validation_cohort_ids = (1..28).map { "ID-%02d".format(it) },
                cohort_access_count = 0,
                android_ready = false,
                device_parity_receipt_sha256 = null,
                holdout_evaluated = false,
                clinical_validation_status = "external_validation_required"
            )

        manifest.requireResearchValid()
    }

    @Test
    fun nestedOuterOofCalibrationIsAcceptedAsDevelopmentOnlyEvidence() {
        val manifest =
            validResources().manifest.copy(
                schema_version = "onnx-research-v1",
                artifact_status = "research_candidate",
                evaluation_scope = "development_only",
                release_status = "blocked_existing_holdout_contract_mismatch",
                gate_passed = true,
                research_gate_passed = true,
                balanced_accuracy_development = 0.80,
                f1_development = 0.80,
                roc_auc_development = 0.84,
                performance_tier = "preferred",
                deployment_scope = "research",
                research_disclaimer = "Model penelitian — development only. Bukan diagnosis klinis.",
                disclaimer_required = true,
                validation_cohort_ids = (1..28).map { "ID-%02d".format(it) },
                cohort_access_count = 0,
                android_ready = false,
                device_parity_receipt_sha256 = null,
                holdout_evaluated = false,
                clinical_validation_status = "external_validation_required",
                probability_calibration =
                validResources().manifest.probability_calibration.copy(
                    fitted_on = "nested_outer_development_oof",
                    calibration_status = "validated_nested_outer_oof"
                )
            )

        manifest.requireResearchValid()
    }

    @Test
    fun internalManifestUsesFrozenTwentyEightFourContractWithoutLegacyReadinessFlags() {
        val manifest =
            validResources().manifest.copy(
                schema_version = "internal-android-v2",
                artifact_status = "internal_candidate",
                evaluation_scope = "internal_32_participants",
                validation_scope = "internal_32_participants",
                claims_policy = "decision_support_only",
                release_status = "internal_candidate",
                gate_passed = true,
                research_gate_passed = true,
                balanced_accuracy_development = 0.80,
                f1_development = 0.80,
                roc_auc_development = 0.84,
                balanced_accuracy_internal_test = 0.75,
                f1_internal_test = 0.74,
                roc_auc_internal_test = 0.76,
                performance_tier = "research_usable",
                deployment_scope = "internal",
                research_disclaimer = "",
                disclaimer_required = false,
                validation_cohort_ids = (1..28).map { "ID-%02d".format(it) },
                cohort_access_count = 1,
                android_ready = false,
                device_parity_receipt_sha256 = null,
                holdout_evaluated = false,
                clinical_validation_status = "",
                hrv_prv_status = "GO",
                training_admission_status = "GO",
                development_model_status = "GO",
                internal_test_status = "GO",
                onnx_export_status = "GO",
                android_device_parity_status = "PENDING",
                internal_android_status = "PENDING_DEVICE_PARITY",
                internal_test_participants = listOf("ID-29", "ID-30", "ID-31", "ID-32")
            )

        manifest.requireInternalValid()
        assertTrue(manifest.sessionContractStatus() is ModelContractStatus.Ready)
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(cohort_access_count = 0).requireInternalValid()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest.copy(android_ready = true).requireInternalValid()
        }
        assertTrue(
            manifest.copy(cohort_access_count = 0).sessionContractStatus() is ModelContractStatus.Unavailable
        )
    }

    @Test
    fun internalManifestAllowsOmittedLegacyStatusFields() {
        val manifest =
            validResources().manifest.copy(
                schema_version = "internal-android-v2",
                artifact_status = "internal_candidate",
                evaluation_scope = "internal_32_participants",
                validation_scope = "internal_32_participants",
                claims_policy = "decision_support_only",
                release_status = "internal_candidate",
                gate_passed = true,
                research_gate_passed = true,
                balanced_accuracy_development = 0.80,
                f1_development = 0.80,
                roc_auc_development = 0.84,
                balanced_accuracy_internal_test = 0.75,
                f1_internal_test = 0.74,
                roc_auc_internal_test = 0.76,
                performance_tier = "research_usable",
                deployment_scope = "internal",
                validation_cohort_ids = (1..28).map { "ID-%02d".format(it) },
                cohort_access_count = 1,
                hrv_prv_status = "GO",
                training_admission_status = "GO",
                development_model_status = "GO",
                internal_test_status = "GO",
                onnx_export_status = "GO",
                android_device_parity_status = "PENDING",
                internal_android_status = "PENDING_DEVICE_PARITY",
                internal_test_participants = listOf("ID-29", "ID-30", "ID-31", "ID-32")
            )
        val json = Gson().toJson(manifest)
        val objectJson = JsonParser.parseString(json).asJsonObject.apply {
            remove("android_ready")
            remove("clinical_validation_status")
        }

        Gson().fromJson(objectJson, ModelManifest::class.java).requireInternalValid()
    }

    @Test
    fun manifestRejectsInconsistentExpectedWindowCount() {
        val manifest = validResources().manifest.copy(expected_windows_per_session = 6)

        assertThrows(IllegalArgumentException::class.java) { manifest.requireValid() }
    }

    @Suppress("LongMethod")
    private fun validResources(): ModelResources = ModelResources(
        manifest =
        ModelManifest(
            schema_version = "1.0.0",
            model_family = "classical",
            model_version = "test-model",
            preprocessing_version = "v3.0.0",
            feature_version = "v3",
            sensor_channel = "ppg_green",
            feature_order = List(34) { "feature_$it" },
            probability_threshold = 0.5f,
            onnx_sha256 = "a".repeat(64),
            inputs = listOf(TensorContract("float_input", "float32", listOf(1, 34))),
            output = TensorContract("stress_probability", "float32", listOf(1)),
            release_status = "accepted",
            gate_passed = true,
            balanced_accuracy_gate = 0.80,
            balanced_accuracy_holdout = 0.81,
            f1_holdout = 0.79,
            roc_auc_holdout = 0.84,
            performance_policy_version = "ml_dl_tiered_v1",
            performance_policy_sha256 = "d".repeat(64),
            performance_tier = "preferred",
            deployment_scope = "standard",
            validation_cohort_ids = listOf("ID-29", "ID-30", "ID-31", "ID-32"),
            cohort_access_count = 1,
            threshold_source = "participant_grouped_development_validation",
            candidate_bundle_sha256 = "b".repeat(64),
            probability_calibration =
            ProbabilityCalibration(
                method = "platt",
                version = "test-calibration",
                slope = 1.0,
                intercept = 0.0,
                fitted_cohort = (1..28).map { "ID-%02d".format(it) },
                fitted_on = "participant_grouped_development_oof",
                calibration_status = "validated_development_oof",
                brier_score = 0.2,
                log_loss = 0.5,
                ece = 0.1
            ),
            source_rate_hz = 25.0,
            model_rate_hz = 25.0,
            filter_algorithm = "butterworth_sosfiltfilt",
            filter_order = 4,
            filter_low_cut_hz = 0.5,
            filter_high_cut_hz = 5.0,
            filter_zero_phase = true,
            filter_pad_policy = "scipy_odd_sosfiltfilt_default",
            filter_design_version = "sos_zpk_bilinear_v1",
            filter_contract_fingerprint = "42d779b01ee96ea1f1042f5a60750fdfaab06e3bb1c842d85b3d7ef3388e777c",
            analysis_seconds = 240,
            window_seconds = 180,
            stride_seconds = 10,
            expected_windows_per_session = 7,
            device_parity_receipt_sha256 = "c".repeat(64),
            android_ready = true
        ),
        modelBytes = byteArrayOf(1),
        preflightVector = ModelPreflightVector(FloatArray(34) { 0.5f }, 0.25f)
    )
}
