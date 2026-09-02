package com.stressdetector.mobile

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.impl.foreground.SystemForegroundService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ForegroundServiceManifestTest {

    @Test
    fun workManagerForegroundServiceDeclaresDataSyncType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, SystemForegroundService::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(
            "SystemForegroundService must declare dataSync in the merged manifest",
            serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        )
    }

    @Test
    fun downloadCompletionReceiverIsNotManifestRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receivers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers
            .orEmpty()

        assertTrue(
            "DownloadManager completion receiver must not be registered; WorkManager is canonical",
            receivers.none { it.name.endsWith("GemmaDownloadCompleteReceiver") }
        )
    }
}
