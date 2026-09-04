package br.com.netscript.meshchat

import android.app.Application
import br.com.netscript.meshchat.data.UserPreferences
import br.com.netscript.meshchat.network.MeshNetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Mantém uma única instância de [MeshNetworkManager] durante a vida do
 * processo, já que a sessão de rede (conexões, chaves, rotas) deve
 * sobreviver à troca de fragments pela bottom navigation.
 */
class MeshChatApp : Application() {

    lateinit var userPreferences: UserPreferences
        private set

    lateinit var meshNetworkManager: MeshNetworkManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(this)

        // O deviceId precisa existir antes de criar o manager; como é uma
        // operação rápida de DataStore local, resolvemos de forma bloqueante
        // apenas nesta inicialização única do app.
        val deviceId = runBlocking { userPreferences.getOrCreateDeviceId() }
        var displayName = "Anonymous-${deviceId.take(4)}"

        meshNetworkManager = MeshNetworkManager(this, deviceId, displayName)

        appScope.launch {
            userPreferences.usernameFlow.collect { saved ->
                if (!saved.isNullOrBlank()) {
                    meshNetworkManager.updateDisplayName(saved)
                }
            }
        }
    }
}
