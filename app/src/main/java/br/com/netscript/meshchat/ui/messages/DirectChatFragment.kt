package br.com.netscript.meshchat.ui.messages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import br.com.netscript.meshchat.MainActivity
import br.com.netscript.meshchat.databinding.FragmentDirectChatBinding
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.Toast

/**
 * Chat exclusivo (1-a-1) com um nó específico da mesh, aberto ao tocar em um
 * item na lista de nós. Mostra apenas mensagens diretas trocadas com aquele
 * peer (filtradas por [br.com.netscript.meshchat.data.ChatMessage.conversationId])
 * e envia novas mensagens cifradas ponta-a-ponta via
 * [br.com.netscript.meshchat.network.MeshNetworkManager.sendDirect].
 */
class DirectChatFragment : Fragment() {

    companion object {
        private const val ARG_PEER_ID = "peer_id"
        private const val ARG_PEER_NAME = "peer_name"

        fun newInstance(peerDeviceId: String, peerName: String) = DirectChatFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PEER_ID, peerDeviceId)
                putString(ARG_PEER_NAME, peerName)
            }
        }
    }

    private var _binding: FragmentDirectChatBinding? = null
    private val binding get() = _binding!!

    private val peerDeviceId: String by lazy { requireArguments().getString(ARG_PEER_ID)!! }
    private val peerName: String by lazy { requireArguments().getString(ARG_PEER_NAME)!! }

    private val meshManager get() = (requireActivity() as MainActivity).meshNetworkManager

    private val adapter = MessagesAdapter()

    private val selecionarArquivo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            Toast.makeText(requireContext(), "O arquivo selecionado foi ${uri.path}", Toast.LENGTH_SHORT).show()
        }else{
            Toast.makeText(requireContext(), "Nenhum arquivo foi selecionado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDirectChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPeerName.text = peerName
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                meshManager.sendDirect(peerDeviceId, text)
                binding.etMessage.text?.clear()
            }
        }
        binding.btnFileSend.setOnClickListener {
            selecionarArquivo.launch(arrayOf("*/*"))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                meshManager.messages
                    .map { all -> all.filter { it.conversationId == peerDeviceId } }
                    .collect { conversation ->
                        adapter.submitList(conversation) {
                            if (conversation.isNotEmpty()) {
                                binding.rvMessages.scrollToPosition(conversation.size - 1)
                                //Toast.makeText(requireContext(), "Mensagem recebida de $peerName", Toast.LENGTH_SHORT).show()
                            }
                        }
                        binding.tvEmpty.visibility = if (conversation.isEmpty()) View.VISIBLE else View.GONE
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                meshManager.messages
                    .map { all -> all.filter { it.isBroadcast } }
                    .collect { messages ->
                        //adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            (requireActivity() as MainActivity).showBadgeMural(messages.size)
                        }
                        //}
                        //binding.tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    }
            }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
