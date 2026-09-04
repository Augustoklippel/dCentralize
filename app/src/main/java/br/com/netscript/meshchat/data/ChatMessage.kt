package br.com.netscript.meshchat.data

/**
 * Mensagem de chat trocada na rede mesh, seja enviada por este dispositivo
 * ou recebida (diretamente ou repassada por um nó intermediário).
 *
 * @param conversationId Para mensagens diretas, o deviceId do outro lado da
 *   conversa (o destinatário quando a mensagem é minha, ou o remetente
 *   quando é recebida) — usado para filtrar o histórico de um chat 1-a-1
 *   com um nó específico. Nulo para mensagens de broadcast.
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isBroadcast: Boolean,
    val conversationId: String? = null
)
