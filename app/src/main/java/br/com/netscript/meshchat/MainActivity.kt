package br.com.netscript.meshchat

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import br.com.netscript.meshchat.databinding.ActivityMainBinding
import br.com.netscript.meshchat.network.MeshNetworkManager
import br.com.netscript.meshchat.ui.messages.DirectChatFragment
import br.com.netscript.meshchat.ui.messages.MessagesFragment
import br.com.netscript.meshchat.ui.nodes.NodesFragment
import br.com.netscript.meshchat.ui.settings.SettingsFragment
import kotlin.system.exitProcess
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * Activity única do app. Hospeda os 3 fragments (Nós, Mensagens,
 * Configurações) trocados via [com.google.android.material.bottomnavigation.BottomNavigationView],
 * e é responsável por solicitar as permissões de runtime exigidas pela
 * Nearby Connections API antes de iniciar advertising/discovery.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    var _newDirectMessage: Int = 0
    var _newMuralMessage: Int = 0
    val meshNetworkManager: MeshNetworkManager
        get() = (application as MeshChatApp).meshNetworkManager

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultados individuais tratados pela verificação em onResume/UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestMissingPermissions()

        if (savedInstanceState == null) {
            showFragment(NodesFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_nodes -> NodesFragment()
                R.id.nav_messages -> MessagesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.popBackStack("direct_chat", androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            showFragment(fragment)
            true
        }
        //val bottomNavigationView = binding.bottomNav
        //val  badgeDirect = bottomNavigationView.getOrCreateBadge(R.id.nav_nodes)
        //val  badgeMural = bottomNavigationView.getOrCreateBadge(R.id.nav_messages)
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /**
     * Abre um chat exclusivo (1-a-1) com o nó [peerDeviceId]. Diferente da
     * navegação da bottom bar, esta transação é empilhada no back stack,
     * então o botão voltar do sistema retorna para a lista de nós.
     */
    fun openDirectChat(peerDeviceId: String, peerName: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, DirectChatFragment.newInstance(peerDeviceId, peerName))
            .addToBackStack("direct_chat")
            .commit()
    }
    fun showBadgeMural(count: Int) {
        if (count > _newMuralMessage) {
            playAlertSound(this)
            _newMuralMessage = count
        }
        //Toast.makeText(this, "Mensagens no mural: $count", Toast.LENGTH_SHORT).show()
    }
    fun showBadgeDirect(count: Int) {
        if (count > _newDirectMessage) {
            playAlertSound(this)
            _newDirectMessage = count
        }
        //Toast.makeText(this, "Mensagens diretas: $count", Toast.LENGTH_SHORT).show()
    }
    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    fun playAlertSound(context: Context) {
        try {
            // Obtém o URI do som de notificação padrão do sistema
            val alerta: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Cria o objeto Ringtone e o reproduz
            val ringtone = RingtoneManager.getRingtone(context, alerta)
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A sessão de rede continua rodando na Application enquanto o
        // processo existir; não paramos aqui para não derrubar conexões
        // ativas apenas por rotação de tela ou troca de fragment.
    }

    fun exitApp(item: MenuItem) {
        val mBuilder = AlertDialog.Builder(this)
            .setTitle("Confirmar")
            .setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim", null)
            .setNegativeButton("Não", null)
            .show()

        // Function for the positive button
        // is programmed to exit the application
        val mPositiveButton = mBuilder.getButton(AlertDialog.BUTTON_POSITIVE)
        mPositiveButton.setOnClickListener {
            exitProcess(0)
        }
    }
}
