package br.com.netscript.meshchat.ui.nodes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import br.com.netscript.meshchat.MainActivity
import br.com.netscript.meshchat.databinding.FragmentNodesBinding
import br.com.netscript.meshchat.network.MeshStatus
import br.com.netscript.meshchat.R
import kotlinx.coroutines.launch

/**
 * Primeiro fragment da bottom navigation: lista os nós atualmente
 * conectados na rede mesh (diretos e alcançáveis via multi-hop) e permite
 * ligar/desligar a busca (advertising + discovery) por novos dispositivos.
 */
class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!

    private val meshManager get() = (requireActivity() as MainActivity).meshNetworkManager
    private val adapter = NodesAdapter { node ->
        val deviceId = node.deviceId ?: return@NodesAdapter
        (requireActivity() as MainActivity).openDirectChat(deviceId, node.displayName)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNodes.adapter = adapter

        binding.btnDiscover.setOnClickListener {
            if (meshManager.status.value == MeshStatus.IDLE) {
                meshManager.start()
                binding.btnDiscover.setBackgroundResource(R.drawable.button_online)
            } else {
                meshManager.stop()
                binding.btnDiscover.setBackgroundResource(R.drawable.button_offline)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    meshManager.nodes.collect { nodes ->
                        adapter.submitList(nodes)
                        binding.tvEmpty.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    meshManager.status.collect { status ->
                        binding.tvStatus.setText(
                            when (status) {
                                MeshStatus.IDLE -> br.com.netscript.meshchat.R.string.status_idle
                                MeshStatus.ADVERTISING_AND_DISCOVERING -> br.com.netscript.meshchat.R.string.status_discovering
                            }
                        )
                        binding.btnDiscover.setText(
                            if (status == MeshStatus.IDLE)
                                br.com.netscript.meshchat.R.string.btn_discover
                            else
                                br.com.netscript.meshchat.R.string.btn_stop_discover
                        )
                        binding.btnDiscover.setBackgroundResource(
                            if (status == MeshStatus.IDLE)
                                br.com.netscript.meshchat.R.drawable.button_offline
                            else
                                br.com.netscript.meshchat.R.drawable.button_online
                        )
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
