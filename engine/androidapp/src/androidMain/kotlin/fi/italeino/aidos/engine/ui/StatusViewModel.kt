package fi.italeino.aidos.engine.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for Engine Status and Control (RFC-0103 Phase E).
 *
 * Everything here is read from the live system: resident models from the runtime, RAM from
 * ActivityManager, and connected apps from the persisted approval decisions.
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning: StateFlow<Boolean> = _isEngineRunning.asStateFlow()

    private val _residentModels = MutableStateFlow<List<ResidentModel>>(emptyList())
    val residentModels: StateFlow<List<ResidentModel>> = _residentModels.asStateFlow()

    private val _memory = MutableStateFlow(readMemory())
    val memory: StateFlow<MemoryBudget> = _memory.asStateFlow()

    /** Display names of apps the user has approved to use the Engine. */
    private val _approvedApps = MutableStateFlow<List<String>>(emptyList())
    val approvedApps: StateFlow<List<String>> = _approvedApps.asStateFlow()

    private val _pendingAppCount = MutableStateFlow(0)
    val pendingAppCount: StateFlow<Int> = _pendingAppCount.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _memory.value = readMemory()

        val service = EngineService.instance
        _isEngineRunning.value = service?.isRunning ?: false
        if (service == null) {
            _residentModels.value = emptyList()
            return
        }

        viewModelScope.launch {
            val approvals = service.approvalStore?.listAllApprovals().orEmpty()
            _approvedApps.value = approvals
                .filter { it.status == AppApprovalStatus.APPROVED }
                .map { it.displayName }
            _pendingAppCount.value = approvals.count { it.status == AppApprovalStatus.PENDING }

            val runtime = service.modelRuntime ?: return@launch
            val installedById = withContext(Dispatchers.IO) {
                service.catalogManager?.listInstalled()?.getOrNull()
            }.orEmpty().associateBy { it.modelId }
            val now = System.currentTimeMillis()
            _residentModels.value = runtime.loaded().map { id ->
                val installed = installedById[id]
                ResidentModel(
                    id = id,
                    displayName = installed?.userLabel ?: id,
                    quantization = installed?.quantization ?: "unknown",
                    loadedAgoMs = runtime.loadedAtMillis(id)?.let { now - it } ?: 0L,
                )
            }
        }
    }

    private fun readMemory(): MemoryBudget {
        val activityManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val mb = 1024L * 1024L
        return MemoryBudget(
            usedMB = ((info.totalMem - info.availMem) / mb).toInt(),
            totalMB = (info.totalMem / mb).toInt(),
        )
    }
}
