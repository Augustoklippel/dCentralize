package br.com.netscript.meshchat.ui.nodes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.netscript.meshchat.data.Node
import br.com.netscript.meshchat.data.NodeStatus
import br.com.netscript.meshchat.databinding.ItemNodeBinding

class NodesAdapter(
    private val onNodeClick: (Node) -> Unit
) : ListAdapter<Node, NodesAdapter.NodeViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Node>() {
            override fun areItemsTheSame(oldItem: Node, newItem: Node) =
                oldItem.endpointId == newItem.endpointId

            override fun areContentsTheSame(oldItem: Node, newItem: Node) =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val binding = ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NodeViewHolder(binding, onNodeClick)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NodeViewHolder(
        private val binding: ItemNodeBinding,
        private val onNodeClick: (Node) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(node: Node) {
            binding.tvNodeName.text = node.displayName
            binding.tvNodeStatus.text = when (node.status) {
                NodeStatus.CONNECTED -> "Conectado"
                NodeStatus.CONNECTING -> "Conectando…"
            }
            binding.tvNodeHops.text = if (node.hopCount == 0) "Direto" else "${node.hopCount} saltos"
            binding.dotStatus.setBackgroundColor(
                if (node.status == NodeStatus.CONNECTED)
                    binding.root.context.getColor(br.com.netscript.meshchat.R.color.online_dot)
                else
                    binding.root.context.getColor(br.com.netscript.meshchat.R.color.text_secondary)
            )
            // Só é possível abrir um chat exclusivo quando o deviceId lógico
            // do nó já é conhecido (HELLO trocado) e ele está conectado.
            val clickable = node.status == NodeStatus.CONNECTED && node.deviceId != null
            binding.root.isClickable = clickable
            binding.root.alpha = if (clickable) 1f else 0.5f
            binding.root.setOnClickListener { if (clickable) onNodeClick(node) }
        }
    }
}
