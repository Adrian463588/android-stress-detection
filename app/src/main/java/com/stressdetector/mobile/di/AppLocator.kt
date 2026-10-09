package com.stressdetector.mobile.di

import android.content.Context
import com.stressdetector.mobile.audio.AndroidAudioCaptureEngine
import com.stressdetector.mobile.audio.AudioCaptureOwnership
import com.stressdetector.mobile.audio.AudioFocusCoordinator
import com.stressdetector.mobile.audio.AudioRecordVoiceInputEngine
import com.stressdetector.mobile.audio.LocalSpeechRuntime
import com.stressdetector.mobile.audio.MediaAudioPlaybackEngine
import com.stressdetector.mobile.audio.PiperVoiceOutputEngine
import com.stressdetector.mobile.audio.SherpaOnnxSpeechRuntime
import com.stressdetector.mobile.audio.SpeechRuntimeCoordinator
import com.stressdetector.mobile.audio.SpeechRuntimeMode
import com.stressdetector.mobile.consent.ConsentRepository
import com.stressdetector.mobile.consent.EncryptedConsentRepository
import com.stressdetector.mobile.data.IPpgRepository
import com.stressdetector.mobile.data.IWearConnectionRepository
import com.stressdetector.mobile.data.PpgRepositoryImpl
import com.stressdetector.mobile.data.WearCommandSender
import com.stressdetector.mobile.data.WearConnectionRepositoryImpl
import com.stressdetector.mobile.data.WearSessionCoordinator
import com.stressdetector.mobile.data.db.KnowledgeBaseDatabase
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.reminder.EncryptedAiReminderSecretStore
import com.stressdetector.mobile.data.reminder.RoomAiReminderRepository
import com.stressdetector.mobile.data.reminder.SharedPreferencesAiReminderSettings
import com.stressdetector.mobile.data.repository.RoomCounselorPersonaRepository
import com.stressdetector.mobile.data.repository.RoomLiveMonitorRecordStore
import com.stressdetector.mobile.data.repository.SafCounselorPersonaRepository
import com.stressdetector.mobile.data.search.FileBackedWebResearchEvidenceStore
import com.stressdetector.mobile.data.search.HtmlWebSearchDiscovery
import com.stressdetector.mobile.data.search.OkHttpKsoupWebPageScraper
import com.stressdetector.mobile.data.search.SharedPreferencesWebConsentStore
import com.stressdetector.mobile.data.search.WebGroundingGatewayImpl
import com.stressdetector.mobile.data.stress.SharedPreferencesStressHistoryContextSettings
import com.stressdetector.mobile.data.voice.SharedPreferencesVoiceSettingsRepository
import com.stressdetector.mobile.diagnostics.DebugLivePpgCapture
import com.stressdetector.mobile.diagnostics.DebugLivePpgCaptureSettings
import com.stressdetector.mobile.domain.IStressInferenceUseCase
import com.stressdetector.mobile.domain.StressInferenceUseCase
import com.stressdetector.mobile.domain.engine.AnnotationRepository
import com.stressdetector.mobile.domain.engine.AssetPromptAssetProvider
import com.stressdetector.mobile.domain.engine.AudioCaptureEngine
import com.stressdetector.mobile.domain.engine.LiteRTLlmEngine
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.RealtimeInferenceEngine
import com.stressdetector.mobile.domain.engine.VerbatimArtifactService
import com.stressdetector.mobile.domain.engine.VerbatimPpgArchive
import com.stressdetector.mobile.domain.engine.VerbatimSessionFinalizer
import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.mobile.domain.reminder.AiReminderCoordinator
import com.stressdetector.mobile.domain.reminder.AiReminderCoordinatorDependencies
import com.stressdetector.mobile.domain.reminder.AiReminderEligibility
import com.stressdetector.mobile.domain.reminder.AiReminderRepository
import com.stressdetector.mobile.domain.reminder.AiReminderScheduler
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.AiReminderSettings
import com.stressdetector.mobile.domain.reminder.DefaultAiReminderCoordinator
import com.stressdetector.mobile.domain.reminder.LiteRtReminderContentGenerator
import com.stressdetector.mobile.domain.reminder.LiteRtReminderSchedulePlanner
import com.stressdetector.mobile.domain.reminder.ReminderContentGenerator
import com.stressdetector.mobile.domain.reminder.ReminderNotificationPublisher
import com.stressdetector.mobile.domain.reminder.ReminderSchedulePlanner
import com.stressdetector.mobile.domain.reminder.WorkManagerAiReminderScheduler
import com.stressdetector.mobile.domain.search.WebConsentStore
import com.stressdetector.mobile.domain.search.WebGroundingGateway
import com.stressdetector.mobile.domain.search.WebPageScraper
import com.stressdetector.mobile.domain.search.WebSearchDiscovery
import com.stressdetector.mobile.domain.stress.RoomStressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextSettings
import com.stressdetector.mobile.domain.voice.VoiceInputEngine
import com.stressdetector.mobile.domain.voice.VoiceOutputEngine
import com.stressdetector.mobile.domain.voice.VoiceSettingsRepository
import com.stressdetector.mobile.lifecycle.AiReminderLifecycleDependencies
import com.stressdetector.mobile.lifecycle.DataLifecycleService
import com.stressdetector.mobile.lifecycle.LocalDataLifecycleService
import com.stressdetector.mobile.lifecycle.PortableUserDataArchive
import com.stressdetector.mobile.lifecycle.SafUserBackupService
import com.stressdetector.mobile.ml.StressInferenceRuntime
import com.stressdetector.mobile.ml.artifacts.EmbeddingArtifactManager
import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog
import com.stressdetector.mobile.ml.rag.AndroidEncryptedDocumentSourceStore
import com.stressdetector.mobile.ml.rag.AssetBundledLocalCorpus
import com.stressdetector.mobile.ml.rag.AssetRagRuntimeContract
import com.stressdetector.mobile.ml.rag.AssetRetrievalBenchmark
import com.stressdetector.mobile.ml.rag.BundledLocalRagRetriever
import com.stressdetector.mobile.ml.rag.DocumentIngestionService
import com.stressdetector.mobile.ml.rag.DocumentParser
import com.stressdetector.mobile.ml.rag.KnowledgeBaseIndexPublisher
import com.stressdetector.mobile.ml.rag.KnowledgeBaseProcessor
import com.stressdetector.mobile.ml.rag.LegacyKnowledgeBaseMigrator
import com.stressdetector.mobile.ml.rag.LocalEmbeddingModel
import com.stressdetector.mobile.ml.rag.LocalRagRetrieverImpl
import com.stressdetector.mobile.ml.rag.RagRuntimeContract
import com.stressdetector.mobile.ml.rag.RetrievalPolicyBinding
import com.stressdetector.mobile.ml.rag.TextChunker
import com.stressdetector.mobile.ml.rag.TieredRagRetriever
import com.stressdetector.mobile.ml.speech.SpeechBundleCatalog
import com.stressdetector.mobile.ml.speech.SpeechBundleDescriptor
import com.stressdetector.mobile.ml.speech.SpeechBundleDownloadManager
import com.stressdetector.mobile.ml.speech.SpeechModelBundleManager
import com.stressdetector.mobile.ml.storage.DurableArtifactCoordinator
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.notification.AndroidAiReminderNotificationPublisher
import com.stressdetector.mobile.security.AndroidEncryptedStorageService
import com.stressdetector.mobile.security.AndroidKeystoreKeyManager
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.mobile.security.KeyManager
import com.stressdetector.mobile.security.KnowledgeBasePassphraseStore
import com.stressdetector.mobile.signal.AssetLiveMonitorRuntimeContract
import com.stressdetector.mobile.signal.RealtimeInferenceEngineImpl
import com.stressdetector.shared.PpgType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

object AppLocator {
    val keyManager: KeyManager by lazy { AndroidKeystoreKeyManager() }

    @Volatile private var encryptedStorageInstance: EncryptedStorageService? = null
    fun encryptedStorage(context: Context): EncryptedStorageService = encryptedStorageInstance ?: synchronized(this) {
        encryptedStorageInstance ?: AndroidEncryptedStorageService(context.applicationContext, keyManager)
            .also { encryptedStorageInstance = it }
    }

    @Volatile private var encryptedDocumentSourceStoreInstance: AndroidEncryptedDocumentSourceStore? = null
    fun encryptedDocumentSourceStore(context: Context): AndroidEncryptedDocumentSourceStore =
        encryptedDocumentSourceStoreInstance ?: synchronized(this) {
            encryptedDocumentSourceStoreInstance ?: AndroidEncryptedDocumentSourceStore(
                context.applicationContext,
                encryptedStorage(context)
            ).also { encryptedDocumentSourceStoreInstance = it }
        }

    @Volatile private var consentRepositoryInstance: ConsentRepository? = null
    fun consentRepository(context: Context): ConsentRepository = consentRepositoryInstance ?: synchronized(this) {
        consentRepositoryInstance ?: EncryptedConsentRepository(context.applicationContext)
            .also { consentRepositoryInstance = it }
    }

    @Volatile private var consentManagerInstance: com.stressdetector.mobile.consent.ConsentManager? = null
    fun consentManager(context: Context): com.stressdetector.mobile.consent.ConsentManager = consentManagerInstance ?: synchronized(this) {
        consentManagerInstance ?: com.stressdetector.mobile.consent.ConsentManager(consentRepository(context))
            .also { consentManagerInstance = it }
    }

    @Volatile private var dataLifecycleInstance: DataLifecycleService? = null
    fun dataLifecycleService(context: Context): DataLifecycleService = dataLifecycleInstance ?: synchronized(this) {
        dataLifecycleInstance ?: LocalDataLifecycleService(
            context.applicationContext,
            database(context),
            encryptedStorage(context),
            keyManager,
            AiReminderLifecycleDependencies(
                secretStore = aiReminderSecretStore(context),
                scheduler = aiReminderScheduler(context),
                settings = aiReminderSettings(context)
            )
        ).also { dataLifecycleInstance = it }
    }

    @Volatile private var userBackupServiceInstance: SafUserBackupService? = null
    fun userBackupService(context: Context): SafUserBackupService = userBackupServiceInstance ?: synchronized(this) {
        userBackupServiceInstance ?: SafUserBackupService(
            context.applicationContext,
            database(context),
            modelStorageManager(context),
            PortableUserDataArchive(context.applicationContext, database(context), encryptedStorage(context)),
            keyManager
        ).also { userBackupServiceInstance = it }
    }

    val ppgRepository: IPpgRepository by lazy {
        PpgRepositoryImpl()
    }

    val wearConnectionRepository: IWearConnectionRepository by lazy {
        WearConnectionRepositoryImpl()
    }

    @Volatile private var watchSessionCoordinatorInstance: WatchSessionCoordinator? = null
    fun watchSessionCoordinator(context: Context): WatchSessionCoordinator = watchSessionCoordinatorInstance ?: synchronized(this) {
        watchSessionCoordinatorInstance ?: WearSessionCoordinator(
            context.applicationContext,
            ppgRepository,
            wearConnectionRepository,
            WearCommandSender(context.applicationContext)
        ).also { watchSessionCoordinatorInstance = it }
    }

    @Volatile private var stressInferenceRuntimeInstance: StressInferenceRuntime? = null
    fun stressInferenceRuntime(context: Context): StressInferenceRuntime = stressInferenceRuntimeInstance ?: synchronized(this) {
        stressInferenceRuntimeInstance ?: StressInferenceRuntime(context.applicationContext)
            .also { stressInferenceRuntimeInstance = it }
    }

    @Volatile private var stressInferenceUseCaseInstance: IStressInferenceUseCase? = null
    fun stressInferenceUseCase(context: Context): IStressInferenceUseCase = stressInferenceUseCaseInstance ?: synchronized(this) {
        stressInferenceUseCaseInstance ?: StressInferenceUseCase(
            runtime = stressInferenceRuntime(context),
            context = context.applicationContext
        ).also { stressInferenceUseCaseInstance = it }
    }

    @Volatile private var databaseInstance: StressDatabase? = null
    fun database(context: Context): StressDatabase = databaseInstance ?: synchronized(this) {
        databaseInstance ?: StressDatabase.get(context).also { databaseInstance = it }
    }

    @Volatile private var knowledgeBaseDatabaseInstance: KnowledgeBaseDatabase? = null
    fun knowledgeBaseDatabase(context: Context): KnowledgeBaseDatabase = knowledgeBaseDatabaseInstance ?: synchronized(this) {
        knowledgeBaseDatabaseInstance ?: KnowledgeBaseDatabase.get(
            context.applicationContext,
            KnowledgeBasePassphraseStore(encryptedStorage(context)).getOrCreate()
        ).also { knowledgeBaseDatabaseInstance = it }
    }

    @Volatile private var stressHistoryContextProviderInstance: StressHistoryContextProvider? = null

    @Volatile private var stressHistoryContextSettingsInstance: StressHistoryContextSettings? = null
    fun stressHistoryContextSettings(context: Context): StressHistoryContextSettings =
        stressHistoryContextSettingsInstance ?: synchronized(this) {
            stressHistoryContextSettingsInstance ?: SharedPreferencesStressHistoryContextSettings(
                context.applicationContext
            ).also { stressHistoryContextSettingsInstance = it }
        }

    fun stressHistoryContextProvider(context: Context): StressHistoryContextProvider =
        stressHistoryContextProviderInstance ?: synchronized(this) {
            stressHistoryContextProviderInstance ?: RoomStressHistoryContextProvider(
                dao = database(context).stressSessionDao(),
                settings = stressHistoryContextSettings(context),
                aiConsent = database(context).consentDao()
                    .observeConsent("AI_COUNSELING")
                    .map { it?.isGranted == true }
            ).also { stressHistoryContextProviderInstance = it }
        }

    @Volatile private var counselorPersonaRepositoryInstance: com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository? = null
    fun counselorPersonaRepository(context: Context): com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository =
        counselorPersonaRepositoryInstance ?: synchronized(this) {
            ragRuntimeContract(context).requirePersonaWordLimit(
                com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS
            )
            counselorPersonaRepositoryInstance ?: SafCounselorPersonaRepository(
                context = context.applicationContext,
                storage = modelStorageManager(context),
                cache = RoomCounselorPersonaRepository(database(context).counselorPersonaDao())
            )
                .also { counselorPersonaRepositoryInstance = it }
        }

    @Volatile private var audioCaptureInstance: AudioCaptureEngine? = null
    fun audioCaptureEngine(context: Context): AudioCaptureEngine = audioCaptureInstance ?: synchronized(this) {
        audioCaptureInstance ?: AndroidAudioCaptureEngine(context.applicationContext).also { audioCaptureInstance = it }
    }

    @Volatile private var audioCaptureOwnershipInstance: AudioCaptureOwnership? = null
    fun audioCaptureOwnership(): AudioCaptureOwnership = audioCaptureOwnershipInstance ?: synchronized(this) {
        audioCaptureOwnershipInstance ?: AudioCaptureOwnership().also { audioCaptureOwnershipInstance = it }
    }

    @Volatile private var speechBundleManagerInstance: SpeechModelBundleManager? = null

    @Volatile private var modelStorageManagerInstance: ModelStorageManager? = null

    @Volatile private var localArtifactCatalogInstance: LocalArtifactCatalog? = null
    fun localArtifactCatalog(context: Context): LocalArtifactCatalog = localArtifactCatalogInstance ?: synchronized(this) {
        localArtifactCatalogInstance ?: LocalArtifactCatalog.load(context.applicationContext)
            .also { localArtifactCatalogInstance = it }
    }

    @Volatile private var speechBundleDescriptorInstance: SpeechBundleDescriptor? = null
    fun speechBundleDescriptor(context: Context): SpeechBundleDescriptor = speechBundleDescriptorInstance ?: synchronized(this) {
        speechBundleDescriptorInstance ?: SpeechBundleCatalog.from(localArtifactCatalog(context))
            .also { speechBundleDescriptorInstance = it }
    }

    fun modelStorageManager(context: Context): ModelStorageManager = modelStorageManagerInstance ?: synchronized(this) {
        modelStorageManagerInstance ?: ModelStorageManager(context.applicationContext)
            .also { modelStorageManagerInstance = it }
    }

    @Volatile private var durableArtifactCoordinatorInstance: DurableArtifactCoordinator? = null
    fun durableArtifactCoordinator(context: Context): DurableArtifactCoordinator =
        durableArtifactCoordinatorInstance ?: synchronized(this) {
            durableArtifactCoordinatorInstance ?: DurableArtifactCoordinator(
                storage = modelStorageManager(context),
                modelManager = modelManager(context),
                speechBundleManager = speechBundleManager(context),
                embeddingArtifactManager = embeddingArtifactManager(context)
            ).also { durableArtifactCoordinatorInstance = it }
        }

    fun speechBundleManager(context: Context): SpeechModelBundleManager = speechBundleManagerInstance ?: synchronized(this) {
        speechBundleManagerInstance ?: SpeechModelBundleManager(
            context.applicationContext,
            durableStorage = modelStorageManager(context),
            descriptor = speechBundleDescriptor(context)
        )
            .also { speechBundleManagerInstance = it }
    }

    @Volatile private var speechBundleDownloadManagerInstance: SpeechBundleDownloadManager? = null
    fun speechBundleDownloadManager(context: Context): SpeechBundleDownloadManager =
        speechBundleDownloadManagerInstance ?: synchronized(this) {
            speechBundleDownloadManagerInstance ?: SpeechBundleDownloadManager(
                context.applicationContext,
                speechBundleManager(context),
                descriptor = speechBundleDescriptor(context)
            ).also { speechBundleDownloadManagerInstance = it }
        }

    @Volatile private var speechRuntimeInstance: LocalSpeechRuntime? = null

    @Volatile private var speechRuntimeCoordinatorInstance: SpeechRuntimeCoordinator? = null
    fun speechRuntimeCoordinator(context: Context): SpeechRuntimeCoordinator = speechRuntimeCoordinatorInstance ?: synchronized(this) {
        speechRuntimeCoordinatorInstance ?: SpeechRuntimeCoordinator(
            runtimeFactory = { mode ->
                SherpaOnnxSpeechRuntime(
                    bundleManager = speechBundleManager(context),
                    enableDiarization = mode == SpeechRuntimeMode.VERBATIM
                )
            }
        ).also { speechRuntimeCoordinatorInstance = it }
    }

    fun speechRuntime(context: Context): LocalSpeechRuntime = speechRuntimeInstance ?: synchronized(this) {
        speechRuntimeInstance ?: speechRuntimeCoordinator(context)
            .lease(SpeechRuntimeMode.VERBATIM)
            .also { speechRuntimeInstance = it }
    }

    fun counselorSpeechRuntime(context: Context): LocalSpeechRuntime = speechRuntimeCoordinator(context).lease(SpeechRuntimeMode.AI_VOICE)

    fun voiceInputEngine(context: Context): VoiceInputEngine = AudioRecordVoiceInputEngine(
        context.applicationContext,
        counselorSpeechRuntime(context),
        audioCaptureOwnership(),
        audioFocusCoordinator(context)
    )

    fun voiceOutputEngine(context: Context): VoiceOutputEngine =
        PiperVoiceOutputEngine(speechBundleManager(context), audioFocus = audioFocusCoordinator(context))

    @Volatile private var audioFocusCoordinatorInstance: AudioFocusCoordinator? = null
    fun audioFocusCoordinator(context: Context): AudioFocusCoordinator = audioFocusCoordinatorInstance ?: synchronized(this) {
        audioFocusCoordinatorInstance ?: AudioFocusCoordinator(context.applicationContext)
            .also { audioFocusCoordinatorInstance = it }
    }

    @Volatile private var voiceSettingsRepositoryInstance: VoiceSettingsRepository? = null
    fun voiceSettingsRepository(context: Context): VoiceSettingsRepository = voiceSettingsRepositoryInstance ?: synchronized(this) {
        voiceSettingsRepositoryInstance ?: SharedPreferencesVoiceSettingsRepository(
            context.applicationContext
        ).also { voiceSettingsRepositoryInstance = it }
    }

    @Volatile private var realtimeInferenceInstance: RealtimeInferenceEngine? = null
    fun realtimeInferenceEngine(context: Context): RealtimeInferenceEngine = realtimeInferenceInstance ?: synchronized(this) {
        realtimeInferenceInstance ?: RealtimeInferenceEngineImpl(
            context = context.applicationContext,
            inferenceRuntimeProvider = { stressInferenceRuntime(context) },
            closeRuntimeOnStop = false,
            contractProvider = {
                val runtime = stressInferenceRuntime(context)
                val contracts = PpgType.productionChannels.mapNotNull { channel ->
                    runtime.modelContract(channel)?.let { channel to it }
                }.toMap()
                AssetLiveMonitorRuntimeContract(context.applicationContext).load(contracts)
            }
        ).also { realtimeInferenceInstance = it }
    }

    @Volatile private var llmInstance: OnDeviceLlmEngine? = null
    fun llmEngine(context: Context): OnDeviceLlmEngine = llmInstance ?: synchronized(this) {
        llmInstance ?: LiteRTLlmEngine(context.applicationContext, modelManager(context)).also { llmInstance = it }
    }

    @Volatile private var aiReminderSecretStoreInstance: AiReminderSecretStore? = null
    fun aiReminderSecretStore(context: Context): AiReminderSecretStore = aiReminderSecretStoreInstance ?: synchronized(this) {
        aiReminderSecretStoreInstance ?: EncryptedAiReminderSecretStore(context.applicationContext)
            .also { aiReminderSecretStoreInstance = it }
    }

    @Volatile private var aiReminderSettingsInstance: AiReminderSettings? = null
    fun aiReminderSettings(context: Context): AiReminderSettings = aiReminderSettingsInstance ?: synchronized(this) {
        aiReminderSettingsInstance ?: SharedPreferencesAiReminderSettings(context.applicationContext)
            .also { aiReminderSettingsInstance = it }
    }

    @Volatile private var aiReminderRepositoryInstance: AiReminderRepository? = null
    fun aiReminderRepository(context: Context): AiReminderRepository = aiReminderRepositoryInstance ?: synchronized(this) {
        aiReminderRepositoryInstance ?: RoomAiReminderRepository(
            database(context).aiReminderDao(),
            aiReminderSecretStore(context)
        ).also { aiReminderRepositoryInstance = it }
    }

    @Volatile private var aiReminderEligibilityInstance: AiReminderEligibility? = null
    fun aiReminderEligibility(context: Context): AiReminderEligibility = aiReminderEligibilityInstance ?: synchronized(this) {
        aiReminderEligibilityInstance ?: AiReminderEligibility(
            consentDao = database(context).consentDao(),
            historyProvider = stressHistoryContextProvider(context),
            historySettings = stressHistoryContextSettings(context),
            reminderSettings = aiReminderSettings(context)
        ).also { aiReminderEligibilityInstance = it }
    }

    @Volatile private var aiReminderSchedulePlannerInstance: ReminderSchedulePlanner? = null
    fun aiReminderSchedulePlanner(context: Context): ReminderSchedulePlanner = aiReminderSchedulePlannerInstance ?: synchronized(this) {
        aiReminderSchedulePlannerInstance ?: LiteRtReminderSchedulePlanner(llmEngine(context))
            .also { aiReminderSchedulePlannerInstance = it }
    }

    @Volatile private var aiReminderContentGeneratorInstance: ReminderContentGenerator? = null
    fun aiReminderContentGenerator(context: Context): ReminderContentGenerator = aiReminderContentGeneratorInstance ?: synchronized(this) {
        aiReminderContentGeneratorInstance ?: LiteRtReminderContentGenerator(llmEngine(context))
            .also { aiReminderContentGeneratorInstance = it }
    }

    @Volatile private var aiReminderNotificationPublisherInstance: ReminderNotificationPublisher? = null
    fun aiReminderNotificationPublisher(context: Context): ReminderNotificationPublisher =
        aiReminderNotificationPublisherInstance ?: synchronized(this) {
            aiReminderNotificationPublisherInstance ?: AndroidAiReminderNotificationPublisher(context.applicationContext)
                .also { aiReminderNotificationPublisherInstance = it }
        }

    @Volatile private var aiReminderSchedulerInstance: AiReminderScheduler? = null
    fun aiReminderScheduler(context: Context): AiReminderScheduler = aiReminderSchedulerInstance ?: synchronized(this) {
        aiReminderSchedulerInstance ?: WorkManagerAiReminderScheduler(context.applicationContext)
            .also { aiReminderSchedulerInstance = it }
    }

    @Volatile private var aiReminderCoordinatorInstance: AiReminderCoordinator? = null
    fun aiReminderCoordinator(context: Context): AiReminderCoordinator = aiReminderCoordinatorInstance ?: synchronized(this) {
        aiReminderCoordinatorInstance ?: DefaultAiReminderCoordinator(
            AiReminderCoordinatorDependencies(
                repository = aiReminderRepository(context),
                scheduler = aiReminderScheduler(context),
                settings = aiReminderSettings(context),
                secretStore = aiReminderSecretStore(context),
                consentDao = database(context).consentDao(),
                stressHistorySettings = stressHistoryContextSettings(context),
                notificationPermissionGranted = {
                    aiReminderNotificationPublisher(context).isPermissionGranted()
                }
            )
        ).also { aiReminderCoordinatorInstance = it }
    }

    @Volatile private var modelManagerInstance: com.stressdetector.mobile.ml.llm.ModelManager? = null
    fun modelManager(context: Context): com.stressdetector.mobile.ml.llm.ModelManager = modelManagerInstance ?: synchronized(this) {
        modelManagerInstance ?: com.stressdetector.mobile.ml.llm.ModelManager(
            context.applicationContext,
            durableStorage = modelStorageManager(context),
            artifactCatalog = localArtifactCatalog(context)
        ).also { modelManagerInstance = it }
    }

    @Volatile private var ragInstance: RagRetriever? = null
    fun ragRetriever(context: Context): RagRetriever = ragInstance ?: synchronized(this) {
        ragInstance ?: ragRuntimeContract(context).let { contract ->
            val policy = contract.retrievalPolicy(retrievalPolicyBinding(context))
            TieredRagRetriever(
                appRag = LocalRagRetrieverImpl(
                    knowledgeBaseDatabase(context).knowledgeBaseDao(),
                    embeddingModel(context),
                    policy,
                    contract.retrievalRanker()
                ),
                bundledLocal = BundledLocalRagRetriever(
                    AssetBundledLocalCorpus(context.applicationContext),
                    policy = policy,
                    ranker = contract.retrievalRanker()
                )
            )
        }.also { ragInstance = it }
    }

    @Volatile private var ragRuntimeContractInstance: RagRuntimeContract? = null
    fun ragRuntimeContract(context: Context): RagRuntimeContract = ragRuntimeContractInstance ?: synchronized(this) {
        ragRuntimeContractInstance ?: AssetRagRuntimeContract(context.applicationContext)
            .load()
            .also { ragRuntimeContractInstance = it }
    }

    /**
     * Retrieval stays abstaining until a versioned local benchmark artifact is supplied.
     * The same binding is used by retrieval and the persisted AI+ context receipt.
     */
    @Volatile private var retrievalPolicyBindingInstance: RetrievalPolicyBinding? = null
    fun retrievalPolicyBinding(context: Context): RetrievalPolicyBinding = retrievalPolicyBindingInstance ?: synchronized(this) {
        // No active-corpus benchmark receipt exists yet. A future packaged artifact alone
        // must not silently calibrate a different corpus or retrieval implementation.
        retrievalPolicyBindingInstance ?: AssetRetrievalBenchmark(openAsset = context.applicationContext.assets::open)
            .load()
            .also { retrievalPolicyBindingInstance = it }
    }

    @Volatile private var embeddingModelInstance: LocalEmbeddingModel? = null

    @Volatile private var embeddingArtifactManagerInstance: EmbeddingArtifactManager? = null

    fun embeddingArtifactManager(context: Context): EmbeddingArtifactManager = embeddingArtifactManagerInstance ?: synchronized(this) {
        embeddingArtifactManagerInstance ?: EmbeddingArtifactManager(
            context.applicationContext,
            localArtifactCatalog(context),
            modelStorageManager(context)
        ).also { embeddingArtifactManagerInstance = it }
    }

    fun embeddingModel(context: Context): LocalEmbeddingModel = embeddingModelInstance ?: synchronized(this) {
        val artifactManager = embeddingArtifactManager(context)
        val artifactRuntimeContract = artifactManager.runtimeContract()
        ragRuntimeContract(context).requireCompatibleWith(artifactRuntimeContract)
        embeddingModelInstance ?: LocalEmbeddingModel(
            context.applicationContext,
            artifactManager.modelFile(),
            artifactManager.tokenizerFile(),
            artifactRuntimeContract
        ).also { embeddingModelInstance = it }
    }

    @Volatile private var documentIngestionInstance: DocumentIngestionService? = null
    fun documentIngestionService(context: Context): DocumentIngestionService = documentIngestionInstance ?: synchronized(this) {
        val ragContract = ragRuntimeContract(context)
        documentIngestionInstance ?: DocumentIngestionService(
            context = context.applicationContext,
            documentDao = database(context).documentDao(),
            parser = DocumentParser(context.applicationContext),
            sourceStore = encryptedDocumentSourceStore(context),
            legacyMigrator = LegacyKnowledgeBaseMigrator(
                context = context.applicationContext,
                documentDao = database(context).documentDao(),
                sourceStore = encryptedDocumentSourceStore(context)
            ),
            knowledgeBasePublisher = KnowledgeBaseIndexPublisher(
                legacyDao = database(context).documentDao(),
                knowledgeBaseDao = knowledgeBaseDatabase(context).knowledgeBaseDao()
            ),
            chunker = TextChunker {
                embeddingModel(context).tokenizerForChunking().getOrThrow()
            },
            embedder = embeddingModel(context),
            chunkingConfig = ragContract.chunkingConfig()
        ).also { documentIngestionInstance = it }
    }

    @Volatile private var knowledgeBaseProcessorInstance: KnowledgeBaseProcessor? = null
    fun knowledgeBaseProcessor(context: Context): KnowledgeBaseProcessor = knowledgeBaseProcessorInstance ?: synchronized(this) {
        KnowledgeBaseProcessor(documentIngestionService(context)).also {
            knowledgeBaseProcessorInstance = it
        }
    }

    @Volatile private var verbatimPpgArchiveInstance: VerbatimPpgArchive? = null
    fun verbatimPpgArchive(context: Context): VerbatimPpgArchive = verbatimPpgArchiveInstance ?: synchronized(this) {
        verbatimPpgArchiveInstance ?: VerbatimPpgArchive(encryptedStorage(context))
            .also { verbatimPpgArchiveInstance = it }
    }

    @Volatile private var debugLivePpgCaptureSettingsInstance: DebugLivePpgCaptureSettings? = null
    fun debugLivePpgCaptureSettings(context: Context): DebugLivePpgCaptureSettings =
        debugLivePpgCaptureSettingsInstance ?: synchronized(this) {
            debugLivePpgCaptureSettingsInstance ?: DebugLivePpgCaptureSettings(context.applicationContext)
                .also { debugLivePpgCaptureSettingsInstance = it }
        }

    @Volatile private var debugLivePpgCaptureInstance: DebugLivePpgCapture? = null
    fun debugLivePpgCapture(context: Context): DebugLivePpgCapture = debugLivePpgCaptureInstance ?: synchronized(this) {
        debugLivePpgCaptureInstance ?: DebugLivePpgCapture(
            rootDirectory = java.io.File(
                context.applicationContext.filesDir,
                DebugLivePpgCapture.DIRECTORY_NAME
            ),
            enabled = { debugLivePpgCaptureSettings(context.applicationContext).isEnabled }
        ).also { debugLivePpgCaptureInstance = it }
    }

    @Volatile private var annotationRepoInstance: AnnotationRepository? = null
    fun annotationRepository(context: Context): AnnotationRepository = annotationRepoInstance ?: synchronized(this) {
        annotationRepoInstance
            ?: com.stressdetector.mobile.data.repository.EncryptedAnnotationRepository(context).also { annotationRepoInstance = it }
    }

    @Volatile private var unifiedSessionRepositoryInstance: com.stressdetector.mobile.data.repository.UnifiedSessionRepository? = null
    fun unifiedSessionRepository(context: Context): com.stressdetector.mobile.data.repository.UnifiedSessionRepository =
        unifiedSessionRepositoryInstance ?: synchronized(this) {
            unifiedSessionRepositoryInstance
                ?: com.stressdetector.mobile.data.repository.UnifiedSessionRepository(database(context)).also {
                    unifiedSessionRepositoryInstance =
                        it
                }
        }

    fun audioPlaybackEngine(context: Context): com.stressdetector.mobile.domain.engine.AudioPlaybackEngine =
        MediaAudioPlaybackEngine(audioFocusCoordinator = audioFocusCoordinator(context))

    @Volatile private var audioIntelligenceEngineInstance: com.stressdetector.mobile.domain.engine.AudioIntelligenceEngine? = null
    fun audioIntelligenceEngine(context: Context): com.stressdetector.mobile.domain.engine.AudioIntelligenceEngine =
        audioIntelligenceEngineInstance ?: synchronized(this) {
            audioIntelligenceEngineInstance ?: com.stressdetector.mobile.audio.RealAudioIntelligenceEngine(
                context,
                encryptedStorage(context),
                speechRuntime(context),
                audioCaptureOwnership(),
                audioFocusCoordinator(context)
            ).also { audioIntelligenceEngineInstance = it }
        }

    fun verbatimArtifactService(context: Context): VerbatimArtifactService =
        VerbatimArtifactService(context.applicationContext, encryptedStorage(context))

    @Volatile private var webHttpClientInstance: OkHttpClient? = null
    private fun webHttpClient(): OkHttpClient = webHttpClientInstance ?: synchronized(this) {
        webHttpClientInstance ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
            .also { webHttpClientInstance = it }
    }

    @Volatile private var webPageScraperInstance: WebPageScraper? = null
    fun webPageScraper(): WebPageScraper = webPageScraperInstance ?: synchronized(this) {
        webPageScraperInstance ?: OkHttpKsoupWebPageScraper(webHttpClient())
            .also { webPageScraperInstance = it }
    }

    @Volatile private var webSearchDiscoveryInstance: WebSearchDiscovery? = null
    fun webSearchDiscovery(): WebSearchDiscovery = webSearchDiscoveryInstance ?: synchronized(this) {
        webSearchDiscoveryInstance ?: HtmlWebSearchDiscovery(webHttpClient())
            .also { webSearchDiscoveryInstance = it }
    }

    @Volatile private var webGroundingGatewayInstance: WebGroundingGateway? = null
    fun webGroundingGateway(context: Context): WebGroundingGateway = webGroundingGatewayInstance ?: synchronized(this) {
        webGroundingGatewayInstance ?: WebGroundingGatewayImpl(
            pageScraper = webPageScraper(),
            evidenceStore = FileBackedWebResearchEvidenceStore(context.applicationContext),
            discovery = webSearchDiscovery()
        ).also { webGroundingGatewayInstance = it }
    }

    @Volatile private var webConsentStoreInstance: WebConsentStore? = null
    fun webConsentStore(context: Context): WebConsentStore = webConsentStoreInstance ?: synchronized(this) {
        webConsentStoreInstance ?: SharedPreferencesWebConsentStore(context.applicationContext)
            .also { webConsentStoreInstance = it }
    }

    @Volatile private var liveBiosignalEngineInstance: com.stressdetector.mobile.domain.engine.LiveBiosignalEngine? = null
    fun liveBiosignalEngine(context: Context): com.stressdetector.mobile.domain.engine.LiveBiosignalEngine =
        liveBiosignalEngineInstance ?: synchronized(this) {
            liveBiosignalEngineInstance ?: com.stressdetector.mobile.signal.RealLiveBiosignalEngine(
                realtimeInferenceEngine(context),
                ppgRepository,
                RoomLiveMonitorRecordStore(database(context).liveMonitorDao()),
                AssetLiveMonitorRuntimeContract(context.applicationContext).loadConfig()
            ).also { liveBiosignalEngineInstance = it }
        }

    @Volatile private var unifiedSessionEngineInstance: com.stressdetector.mobile.domain.engine.UnifiedSessionEngine? = null
    fun unifiedSessionEngine(context: Context): com.stressdetector.mobile.domain.engine.UnifiedSessionEngine =
        unifiedSessionEngineInstance ?: synchronized(this) {
            unifiedSessionEngineInstance ?: com.stressdetector.mobile.domain.engine.UnifiedSessionEngine(
                unifiedSessionRepository(context),
                audioIntelligenceEngine(context),
                liveBiosignalEngine(context),
                watchSessionCoordinator(context),
                consentRepository(context),
                VerbatimSessionFinalizer(
                    encryptedStorage(context),
                    llmEngine(context),
                    AssetPromptAssetProvider(context.applicationContext),
                    ragRetriever(context)
                ),
                annotationRepository(context),
                ppgRepository = ppgRepository,
                verbatimPpgArchive = verbatimPpgArchive(context)
            ).also { unifiedSessionEngineInstance = it }
        }
}
