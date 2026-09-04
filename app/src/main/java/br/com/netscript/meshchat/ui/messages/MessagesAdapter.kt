package br.com.netscript.meshchat.ui.messages

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.netscript.meshchat.data.ChatMessage
import br.com.netscript.meshchat.databinding.ItemMessageReceivedBinding
import br.com.netscript.meshchat.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_SENT = 1
        private const val TYPE_RECEIVED = 2
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isOutgoing) TYPE_SENT else TYPE_RECEIVED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SENT) {
            SentViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false))
        } else {
            ReceivedViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is SentViewHolder -> holder.bind(message)
            is ReceivedViewHolder -> holder.bind(message)
        }
    }

    class SentViewHolder(private val binding: ItemMessageSentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvBody.text = message.body
            val scope = if (message.isBroadcast) "Todos" else "Direto"
            binding.tvMeta.text = "$scope • ${timeFormat.format(Date(message.timestamp))}"
        }
    }

    class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvSender.text = message.senderName
            binding.tvBody.text = message.body
            binding.tvMeta.text = timeFormat.format(Date(message.timestamp))
        }
    }
}
