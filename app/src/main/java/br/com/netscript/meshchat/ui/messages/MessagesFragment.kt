package br.com.netscript.meshchat.ui.messages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import br.com.netscript.meshchat.MainActivity
import br.com.netscript.meshchat.MeshChatApp
import br.com.netscript.meshchat.data.UserPreferences
import br.com.netscript.meshchat.databinding.FragmentMessagesBinding
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Segundo fragment da bottom navigation: histórico de mensagens enviadas e
 * recebidas na mesh, com campo de entrada para novas mensagens (enviadas
 * como broadcast para toda a rede).
 */
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!

    private val meshManager get() = (requireActivity() as MainActivity).meshNetworkManager

    private val adapter = MessagesAdapter()

    private val userPreferences get() = (requireActivity().application as MeshChatApp).userPreferences

    private lateinit var peerDeviceId: String

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as MainActivity).hideBadgeMural()

        //peerDeviceId = userPreferences.//userPreferences.getOrCreateDeviceId()

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                meshManager.sendBroadcast(text)
                binding.etMessage.text?.clear()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                meshManager.messages
                    .map { all -> all.filter { it.isBroadcast } }
                    .collect { messages ->
                        adapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                binding.rvMessages.scrollToPosition(messages.size - 1)
                            }

                        }
                        binding.tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val peerDeviceId = userPreferences.getOrCreateDeviceId()
            //Toast.makeText(requireContext(), "Peer_ID: $peerDeviceId", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                meshManager.messages
                    .map { all -> all.filter { it.conversationId == peerDeviceId } }
                    .collect { messages ->
                        //adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                           (requireActivity() as MainActivity).showBadgeDirect(messages.size)
                        }
                    }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
