package br.com.netscript.meshchat.network

import android.content.Context
import android.util.Log
import br.com.netscript.meshchat.crypto.CryptoManager
import br.com.netscript.meshchat.data.ChatMessage
import br.com.netscript.meshchat.data.Node
import br.com.netscript.meshchat.data.NodeStatus
import br.com.netscript.meshchat.data.LoggerApp
import br.com.netscript.meshchat.ui.TransferState
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import android.net.Uri
import android.provider.OpenableColumns
import br.com.netscript.meshchat.data.FileMetadata
import java.io.File
import java.nio.charset.StandardCharsets

enum class MeshStatus { IDLE, ADVERTISING_AND_DISCOVERING }

/**
 * Gerencia a rede mesh peer-to-peer sobre a Nearby Connections API.
 *
 * Estratégia de topologia: [Strategy.P2P_CLUSTER], que permite que cada
 * dispositivo mantenha múltiplas conexões simultâneas (M-to-M), formando de
 * fato uma malha (mesh) em vez de uma estrela ou apenas pares isolados.
 *
 * Roteamento: como a Nearby Connections só entrega pacotes a vizinhos
 * diretamente conectados, o alcance da mesh (multi-hop) é implementado por
 * flooding controlado: toda mensagem carrega um TTL e um ID único; ao
 * receber uma mensagem nova (ID ainda não visto), o nó a repassa para todos
 * os seus vizinhos diretos, exceto aquele de quem a recebeu, decrementando o
 * TTL, até TTL chegar a zero ou a mensagem já ter sido vista antes
 * (deduplicação por ID).
 *
 * Segurança: mensagens diretas (unicast) são cifradas ponta-a-ponta com
 * AES-256-GCM, cuja chave é derivada via ECDH usando [CryptoManager].
 * Mensagens de broadcast trafegam em texto claro, pois são endereçadas a
 * toda a mesh.
 */
class MeshNetworkManager(
    private val context: Context,
    private val localDeviceId: String,
    private var localDisplayName: String
) {
    companion object {
        private const val TAG = "MeshNetworkManager"
        private const val SERVICE_ID = "br.com.netscript.meshchat.SERVICE"
        private const val DEFAULT_TTL = 5
        private const val MAX_SEEN_IDS = 500

        private const val TYPE_HELLO = "HELLO"
        private const val TYPE_BROADCAST = "BROADCAST"
        private const val TYPE_DIRECT = "DIRECT"
        private const val TYPE_FILE = "FILE"
        private const val TYPE_BYTES = "BYTES"
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val cryptoManager = CryptoManager()

    // endpointId -> nome anunciado (nós diretamente conectados)
    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    // endpointId -> chave pública do peer, para cifrar mensagens diretas
    private val peerPublicKeys = ConcurrentHashMap<String, PublicKey>()
    // deviceId lógico -> endpointId direto por onde ele é alcançável (próximo salto)
    private val routeTable = ConcurrentHashMap<String, String>()
    // IDs de mensagem já processadas, para evitar loops de flooding
    private val seenMessageIds = LinkedHashSetSync<String>(MAX_SEEN_IDS)

    private val _status = MutableStateFlow(MeshStatus.IDLE)
    val status: StateFlow<MeshStatus> = _status

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val meuLogger by lazy { LoggerApp(context) }

    // Fluxos que a UI vai escutar
    val transferProgress = MutableStateFlow(0)
    val currentFileName = MutableStateFlow("")
    val transferStatus = MutableStateFlow<TransferState>(TransferState.Idle)

    fun updateDisplayName(name: String) {
        localDisplayName = name
    }

    // ---------------------------------------------------------------------
    // Ciclo de vida: iniciar / parar advertising + discovery simultâneos
    // ---------------------------------------------------------------------

    fun start() {
        startAdvertising()
        startDiscovery()
        _status.value = MeshStatus.ADVERTISING_AND_DISCOVERING
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        peerPublicKeys.clear()
        routeTable.clear()
        _nodes.value = emptyList()
        _status.value = MeshStatus.IDLE
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        meuLogger.gravarLog("MeshNetworkManager", "O anunciamento foi iniciado.")
        connectionsClient.startAdvertising(
            localDisplayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnFailureListener { e -> Log.w(TAG, "Falha ao iniciar advertising", e) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        meuLogger.gravarLog("MeshNetworkManager", "A descoberta foi finalizada.")
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnFailureListener { e -> Log.w(TAG, "Falha ao iniciar discovery", e) }
    }

    // ---------------------------------------------------------------------
    // Descoberta de endpoints
    // ---------------------------------------------------------------------

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                localDisplayName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e -> Log.w(TAG, "Falha ao solicitar conexão com $endpointId", e) }
        }

        override fun onEndpointLost(endpointId: String) {
            // Nada a fazer aqui diretamente: a perda efetiva de conexão é
            // tratada em onDisconnected do ConnectionLifecycleCallback.
        }
    }

    // ---------------------------------------------------------------------
    // Ciclo de vida da conexão
    // ---------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Aceita automaticamente; em um app de produção convém confirmar
            // via UI (comparação de código) antes de aceitar.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            upsertNode(endpointId, info.endpointName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTING, deviceId = null)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints[endpointId] = connectedEndpoints[endpointId] ?: endpointId
                upsertNode(endpointId, connectedEndpoints[endpointId] ?: endpointId, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = null)
                sendHello(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
                removeNode(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            peerPublicKeys.remove(endpointId)
            routeTable.entries.removeAll { it.value == endpointId }
            removeNode(endpointId)
        }
    }

    // ---------------------------------------------------------------------
    // Envio de mensagens
    // ---------------------------------------------------------------------

    /** Envia uma mensagem para toda a mesh (broadcast, sem cifra ponta-a-ponta). */
    fun sendBroadcast(body: String) {
        val messageId = UUID.randomUUID().toString()
        val json = JSONObject().apply {
            put("type", TYPE_BROADCAST)
            put("id", messageId)
            put("originId", localDeviceId)
            put("originName", localDisplayName)
            put("ttl", DEFAULT_TTL)
            put("body", body)
            put("ts", System.currentTimeMillis())
        }
        seenMessageIds.add(messageId)
        broadcastToAllDirectPeers(json, excludeEndpointId = null)
        appendLocalMessage(
            ChatMessage(
                id = messageId,
                senderId = localDeviceId,
                senderName = localDisplayName,
                body = body,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isBroadcast = true
            )
        )
    }

    /**
     * Envia uma mensagem direta e cifrada para [targetDeviceId]. Se o
     * destino não for um vizinho direto, a mensagem é encaminhada pela
     * rota conhecida (flooding com TTL) até alcançá-lo.
     */
    fun sendDirect(targetDeviceId: String, body: String) {
        val nextHopEndpoint = routeTable[targetDeviceId] ?: run {
            Log.w(TAG, "Nenhuma rota conhecida para $targetDeviceId")
            return
        }
        val peerKey = peerPublicKeys[nextHopEndpoint]
        val messageId = UUID.randomUUID().toString()

        val encryptedBody: String
        val isEncrypted: Boolean
        if (peerKey != null) {
            val sharedSecret: SecretKeySpec = cryptoManager.deriveSharedSecret(peerKey)
            encryptedBody = cryptoManager.encrypt(body, sharedSecret)
            isEncrypted = true
        } else {
            encryptedBody = body
            isEncrypted = false
        }

        val json = JSONObject().apply {
            put("type", TYPE_DIRECT)
            put("id", messageId)
            put("originId", localDeviceId)
            put("originName", localDisplayName)
            put("targetId", targetDeviceId)
            put("ttl", DEFAULT_TTL)
            put("encrypted", isEncrypted)
            put("body", encryptedBody)
            put("ts", System.currentTimeMillis())
        }
        seenMessageIds.add(messageId)
        sendPayloadTo(nextHopEndpoint, json)
        appendLocalMessage(
            ChatMessage(
                id = messageId,
                senderId = localDeviceId,
                senderName = localDisplayName,
                body = body,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isBroadcast = false,
                conversationId = targetDeviceId
            )
        )
    }

    private fun sendHello(endpointId: String) {
        val json = JSONObject().apply {
            put("type", TYPE_HELLO)
            put("deviceId", localDeviceId)
            put("displayName", localDisplayName)
            put("publicKey", cryptoManager.publicKeyBase64)
        }
        sendPayloadTo(endpointId, json)
    }

    private fun sendPayloadTo(endpointId: String, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun broadcastToAllDirectPeers(json: JSONObject, excludeEndpointId: String?) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        val targets = connectedEndpoints.keys.filter { it != excludeEndpointId }
        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, payload)
        }
    }

    // ---------------------------------------------------------------------
    // Recebimento de payloads e roteamento (flooding com TTL + dedup)
    // ---------------------------------------------------------------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(fromEndpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Payload malformado recebido de $fromEndpointId", e)
                return
            }
            handleIncoming(fromEndpointId, json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Sem necessidade de tratar progresso para payloads pequenos (bytes).
        }
    }

    private fun handleIncoming(fromEndpointId: String, json: JSONObject) {
        when (json.optString("type")) {
            TYPE_HELLO -> handleHello(fromEndpointId, json)
            TYPE_BROADCAST -> handleBroadcast(fromEndpointId, json)
            TYPE_DIRECT -> handleDirect(fromEndpointId, json)
            TYPE_BYTES -> handleByteFile(fromEndpointId, json)
            TYPE_FILE -> handleFile(fromEndpointId, json)
        }
    }

    private fun handleHello(fromEndpointId: String, json: JSONObject) {
        val deviceId = json.optString("deviceId")
        val displayName = json.optString("displayName")
        val publicKeyB64 = json.optString("publicKey")

        connectedEndpoints[fromEndpointId] = displayName
        routeTable[deviceId] = fromEndpointId
        try {
            peerPublicKeys[fromEndpointId] = cryptoManager.decodePublicKey(publicKeyB64)
        } catch (e: Exception) {
            Log.w(TAG, "Chave pública inválida recebida de $displayName", e)
        }
        upsertNode(fromEndpointId, displayName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = deviceId)
    }

    private fun handleBroadcast(fromEndpointId: String, json: JSONObject) {
        val messageId = json.optString("id")
        if (!seenMessageIds.add(messageId)) return // já processada, evita loop

        val originId = json.optString("originId")
        val originName = json.optString("originName")
        val body = json.optString("body")
        val ts = json.optLong("ts")
        val ttl = json.optInt("ttl", 0)

        if (originId != localDeviceId) {
            appendRemoteMessage(
                ChatMessage(
                    id = messageId,
                    senderId = originId,
                    senderName = originName,
                    body = body,
                    timestamp = ts,
                    isOutgoing = false,
                    isBroadcast = true
                )
            )
            meuLogger.gravarLog("MeshNetworkManager", "A mensagem do Mural foi recebida de $originName. Conteúdo da Mensagem: $body")
        }

        if (ttl > 0) {
            val forwarded = JSONObject(json.toString()).apply { put("ttl", ttl - 1) }
            broadcastToAllDirectPeers(forwarded, excludeEndpointId = fromEndpointId)
            val originName = json.optString("originName")
            val body = json.optString("body")
            meuLogger.gravarLog("MeshNetworkManager", "A mensagem do MURAL de $originName foi retransmitida. Conteúdo da Mensagem: $body")
        }
    }

    private fun handleDirect(fromEndpointId: String, json: JSONObject) {
        val messageId = json.optString("id")
        if (!seenMessageIds.add(messageId)) return

        val targetId = json.optString("targetId")
        val ttl = json.optInt("ttl", 0)
        // Se a mensagem for para mim.
        if (targetId == localDeviceId) {
            val originId = json.optString("originId")
            val originName = json.optString("originName")
            val ts = json.optLong("ts")
            val encrypted = json.optBoolean("encrypted", false)
            val rawBody = json.optString("body")

            val body = if (encrypted) {
                val peerKey = peerPublicKeys[fromEndpointId]
                if (peerKey != null) {
                    try {
                        val secret = cryptoManager.deriveSharedSecret(peerKey)
                        cryptoManager.decrypt(rawBody, secret)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao decifrar mensagem direta de $originName", e)
                        "[mensagem cifrada não pôde ser lida]"
                    }
                } else {
                    "[mensagem cifrada não pôde ser lida]"
                }
            } else rawBody

            appendRemoteMessage(
                ChatMessage(
                    id = messageId,
                    senderId = originId,
                    senderName = originName,
                    body = body,
                    timestamp = ts,
                    isOutgoing = false,
                    isBroadcast = false,
                    conversationId = originId
                )
            )
            val logMessage = if (encrypted) {
                "A mensagem direta foi recebida de $originName. Conteúdo da Mensagem: $body"
            }else "A mensagem sem criptografia foi recebida de $originName. Conteúdo da Mensagem: $body"

            meuLogger.gravarLog("MeshNetworkManager", logMessage)
        } else if (ttl > 0) {
            // Não é para mim: repasso para os demais vizinhos (flooding),
            // já que não tenho garantia de rota exata multi-hop sem um
            // protocolo de roteamento mais sofisticado.
            val forwarded = JSONObject(json.toString()).apply { put("ttl", ttl - 1) }
            broadcastToAllDirectPeers(forwarded, excludeEndpointId = fromEndpointId)

            val originName = json.optString("originName")
            val body = json.optString("body")
            meuLogger.gravarLog("MeshNetworkManager", "A mensagem de $originName foi retransmitida. Conteúdo da Mensagem: $body")
        }
    }
    private fun handleByteFile(fromEndpointId: String, json: JSONObject) {
        //val file = payload.asFile()?.asJavaFile()
        val deviceId = json.optString("deviceId")
        val displayName = json.optString("displayName")
        val publicKeyB64 = json.optString("publicKey")

        connectedEndpoints[fromEndpointId] = displayName
        routeTable[deviceId] = fromEndpointId
        try {
            peerPublicKeys[fromEndpointId] = cryptoManager.decodePublicKey(publicKeyB64)
        } catch (e: Exception) {
            Log.w(TAG, "Chave pública inválida recebida de $displayName", e)
        }
        //upsertNode(fromEndpointId, displayName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = deviceId)
    }
    private fun handleFile(fromEndpointId: String, json: JSONObject) {
        //val file = payload.asFile()?.asJavaFile()
        val deviceId = json.optString("deviceId")
        val displayName = json.optString("displayName")
        val publicKeyB64 = json.optString("publicKey")

        connectedEndpoints[fromEndpointId] = displayName
        routeTable[deviceId] = fromEndpointId
        try {
            peerPublicKeys[fromEndpointId] = cryptoManager.decodePublicKey(publicKeyB64)
        } catch (e: Exception) {
            Log.w(TAG, "Chave pública inválida recebida de $displayName", e)
        }
        //upsertNode(fromEndpointId, displayName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = deviceId)
    }

    // ---------------------------------------------------------------------
    // Helpers de estado observável (StateFlow) para a UI
    // ---------------------------------------------------------------------

    private fun upsertNode(
        endpointId: String,
        displayName: String,
        isDirect: Boolean,
        hopCount: Int,
        status: NodeStatus,
        deviceId: String? = null
    ) {
        // O nó é sempre indexado pelo endpointId físico da Nearby Connections
        // (estável enquanto a conexão dura). O deviceId lógico, quando
        // conhecido via HELLO, já foi registrado em routeTable separadamente.
        _nodes.update { current ->
            val filtered = current.filterNot { it.endpointId == endpointId }
            // Preserva um deviceId já conhecido se esta chamada não trouxer um novo
            // (ex.: atualização de status sem re-receber HELLO).
            val previousDeviceId = current.firstOrNull { it.endpointId == endpointId }?.deviceId
            filtered + Node(
                endpointId = endpointId,
                deviceId = deviceId ?: previousDeviceId,
                displayName = displayName.ifBlank { endpointId },
                isDirect = isDirect,
                hopCount = hopCount,
                messageCount = 0,
                status = status
            )
        }
    }

    private fun removeNode(endpointId: String) {
        _nodes.update { current -> current.filterNot { it.endpointId == endpointId } }
    }

    private fun appendLocalMessage(message: ChatMessage) {
        _messages.update { it + message }
    }

    private fun appendRemoteMessage(message: ChatMessage) {
        _messages.update { it + message }
    }

    // Função auxiliar para salvar o arquivo recebido
    private fun saveReceivedFile(uri: Uri, filename: String) {
        println("Salvando arquivo: $filename em $uri")
    }
}

/**
 * Conjunto sincronizado com tamanho máximo, usado para deduplicar IDs de
 * mensagem vistos durante o flooding sem crescer indefinidamente.
 */
private class LinkedHashSetSync<T>(private val maxSize: Int) {
    private val set = linkedSetOf<T>()

    @Synchronized
    fun add(item: T): Boolean {
        if (set.contains(item)) return false
        set.add(item)
        if (set.size > maxSize) {
            val oldest = set.iterator().next()
            set.remove(oldest)
        }
        return true
    }
}
