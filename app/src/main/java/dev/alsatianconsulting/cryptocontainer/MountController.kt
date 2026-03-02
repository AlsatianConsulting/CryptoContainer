package dev.alsatianconsulting.cryptocontainer

import dev.alsatianconsulting.cryptocontainer.manager.AESCryptManager
import dev.alsatianconsulting.cryptocontainer.manager.VeraCryptManager
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepo
import dev.alsatianconsulting.cryptocontainer.repo.VeraCryptRepository
import dev.alsatianconsulting.cryptocontainer.util.InactivityTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object MountController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val defaultVera = VeraCryptManager()
    private val defaultVeraRepo: VeraCryptRepository = VeraCryptRepo()
    @Volatile
    private var veraOverride: VeraCryptManager? = null
    @Volatile
    private var veraRepoOverride: VeraCryptRepository? = null
    val vera: VeraCryptManager
        get() = veraOverride ?: defaultVera
    val veraRepo: VeraCryptRepository
        get() = veraRepoOverride ?: defaultVeraRepo
    val aes = AESCryptManager()
    private val inactivity = InactivityTimer(scope, timeoutMs = 10 * 60 * 1000L) {
        veraRepo.close()
        vera.unmount()
    }

    fun unmountAll() {
        scope.launch {
            veraRepo.close()
            vera.unmount()
        }
    }

    fun onActivity() {
        inactivity.ping()
    }

    internal fun setTestOverrides(
        veraManager: VeraCryptManager?,
        repository: VeraCryptRepository?
    ) {
        veraOverride = veraManager
        veraRepoOverride = repository
    }
}
