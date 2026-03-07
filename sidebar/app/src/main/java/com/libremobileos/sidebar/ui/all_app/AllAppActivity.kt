package com.libremobileos.sidebar.ui.all_app

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.ui.theme.SidebarTheme
import com.libremobileos.sidebar.utils.Logger

/**
 * @author KindBrave
 * @since 2023/10/25
 */
class AllAppActivity: ComponentActivity() {
    private val logger = Logger(TAG)
    private val viewModel: AllAppViewModel by viewModels { AllAppViewModel.Factory }

    companion object {
        private const val WINDOWING_MODE_AVIUM_FREEFORM = 102
        private const val TAG = "AllAppActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.d("onCreate")

        setContent {
            SidebarTheme {
                AllAppGridView(
                    viewModel = viewModel,
                    onClick = ::onClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        logger.d("onDestroy")
        super.onDestroy()
    }

    private fun onClick(appInfo: AppInfo) {
        val activityOptions = ActivityOptions.makeBasic().apply {
            setLaunchWindowingMode(WINDOWING_MODE_AVIUM_FREEFORM)
        }
        val intent = Intent().apply {
            setClassName(appInfo.packageName, appInfo.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        try {
            startActivityAsUser(intent, activityOptions.toBundle(), UserHandle.of(appInfo.userId))
        } catch (e: Exception) {
            logger.e("Failed to launch ${appInfo.packageName} in freeform: $e")
        }
        finish()
    }
}
