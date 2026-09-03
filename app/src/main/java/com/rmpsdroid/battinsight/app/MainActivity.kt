package com.rmpsdroid.battinsight.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.rmpsdroid.battinsight.capability.CapabilityCoordinator
import com.rmpsdroid.battinsight.capability.CapabilityReport
import com.rmpsdroid.battinsight.platform.AndroidBatteryPropertySource
import com.rmpsdroid.battinsight.platform.AndroidPackageResolutionSource
import com.rmpsdroid.battinsight.platform.AndroidPermissionStateReader
import com.rmpsdroid.battinsight.platform.AndroidShizukuGateway
import com.rmpsdroid.battinsight.platform.AndroidUsageAccessSource
import com.rmpsdroid.battinsight.platform.GrantedAppProcessRunner
import com.rmpsdroid.battinsight.platform.ShizukuProcessRunner
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the coordinator across configuration changes.
 *
 * Wiring is manual on purpose: swapping backends in tests needs constructor injection, and
 * a dependency-injection framework would not yet earn its weight.
 */
class CapabilityViewModel(context: android.content.Context) : ViewModel() {

    private val gateway = AndroidShizukuGateway(context)

    private val coordinator = CapabilityCoordinator(
        grantedAppRunner = GrantedAppProcessRunner(),
        shizukuRunner = ShizukuProcessRunner(gateway),
        shizukuGateway = gateway,
        permissionReader = AndroidPermissionStateReader(context),
        batterySource = AndroidBatteryPropertySource(context),
        usageSource = AndroidUsageAccessSource(context),
        packageSource = AndroidPackageResolutionSource(context),
        scope = viewModelScope,
    )

    val report: StateFlow<CapabilityReport> = coordinator.report

    init {
        coordinator.refresh()
    }

    fun refresh() = coordinator.refresh()

    class Factory(private val context: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CapabilityViewModel(context.applicationContext) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BattInsightApp() }
    }
}

@Composable
private fun BattInsightApp() {
    MaterialTheme {
        val vm: CapabilityViewModel = viewModel(
            factory = CapabilityViewModel.Factory(
                androidx.compose.ui.platform.LocalContext.current,
            ),
        )
        val report by vm.report.collectAsStateWithLifecycle()
        CapabilityCentreScreen(report = report, onRefresh = vm::refresh)
    }
}
