package br.com.netscript.meshchat.data

/**
 * Representa um nó (dispositivo) conhecido na rede mesh.
 *
 * @param endpointId ID do endpoint dado pela Nearby Connections API (só válido
 *   enquanto a conexão direta existe).
 * @param deviceId ID lógico e estável do dispositivo na mesh, recebido via
 *   mensagem HELLO. É este ID (não o endpointId) que deve ser usado para
 *   endereçar mensagens diretas, pois sobrevive a reconexões. Nulo até o
 *   HELLO ser trocado (nó ainda em CONNECTING).
 * @param displayName Nome de usuário anunciado pelo nó.
 * @param isDirect true se há um link físico direto (rádio) com este nó.
 * @param hopCount Número de saltos até este nó (0 = conexão direta, >0 = via mesh).
 * @param messageCount Número de mensagens novas recebidas
 * @param status Estado atual da conexão.
 */
data class Node(
    val endpointId: String,
    val deviceId: String?,
    val displayName: String,
    val isDirect: Boolean,
    val hopCount: Int,
    val messageCount: Int,
    val status: NodeStatus
)

enum class NodeStatus {
    CONNECTING,
    CONNECTED
}
