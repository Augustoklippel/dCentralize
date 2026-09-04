package br.com.netscript.meshchat.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.netscript.meshchat.MainActivity
import br.com.netscript.meshchat.MeshChatApp
import br.com.netscript.meshchat.R
import br.com.netscript.meshchat.data.LoggerApp
import br.com.netscript.meshchat.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Terceiro fragment da bottom navigation: configura e persiste o nome de
 * usuário via Jetpack DataStore. O nome salvo é imediatamente propagado
 * para o [br.com.netscript.meshchat.network.MeshNetworkManager] (via
 * [MeshChatApp]) para ser anunciado aos demais nós da mesh.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val userPreferences get() = (requireActivity().application as MeshChatApp).userPreferences
    private val meshManager get() = (requireActivity() as MainActivity).meshNetworkManager

    private lateinit var meuLogger: LoggerApp

    // 1. Registra o lançador que abre a tela para escolher onde salvar o arquivo
    private val criarArquivoLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        // Executado quando o usuário escolhe a pasta (ex: Downloads) e clica em Salvar
        uri?.let { exportarConteudoParaUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        meuLogger = LoggerApp(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferences.usernameFlow.collect { saved ->
                    if (!saved.isNullOrBlank() && binding.etUsername.text.isNullOrBlank()) {
                        binding.etUsername.setText(saved)
                    }
                }
            }
        }

        binding.btnSave.setOnClickListener {
            val name = binding.etUsername.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                binding.tilUsername.error = getString(R.string.hint_username)
                return@setOnClickListener
            }
            binding.tilUsername.error = null
            viewLifecycleOwner.lifecycleScope.launch {
                userPreferences.saveUsername(name)
                meshManager.updateDisplayName(name)
                Toast.makeText(requireContext(), R.string.msg_saved, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGetLog.setOnClickListener {
            criarArquivoLauncher.launch("app_logger.txt")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

   private fun exportarConteudoParaUri(uriDestino: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val arquivoOrigem = File(requireContext().filesDir, meuLogger.nomeArquivo)

            if (!arquivoOrigem.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Nenhum log gravado ainda.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            var sucesso = false
            try {
                requireActivity().contentResolver.openOutputStream(uriDestino)?.use { outputStream ->
                    FileInputStream(arquivoOrigem).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                sucesso = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                if (sucesso) {
                    Toast.makeText(requireContext(), "Logs salvos com sucesso!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Falha ao salvar logs.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
