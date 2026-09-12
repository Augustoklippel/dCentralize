package br.com.netscript.meshchat.network

import android.content.Context
import android.util.Log
import br.com.netscript.meshchat.crypto.CryptoManager
import br.com.netscript.meshchat.data.ChatMessage
import br.com.netscript.meshchat.data.Node
import br.com.netscript.meshchat.data.NodeStatus
import br.com.netscript.meshchat.data.LoggerApp
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        private const val TYPE_PRESENCE = "PRESENCE"

        private const val PRESENCE_INTERVAL_MS = 15_000L
        private const val PRESENCE_NODE_TIMEOUT_MS = 45_000L
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val cryptoManager = CryptoManager()

    // endpointId -> nome anunciado (nós diretamente conectados)
    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    // deviceId lógico -> chave pública do dono daquele deviceId. Alimentado
    // tanto pelo HELLO (vizinhos diretos) quanto pelos anúncios de presença
    // (que agora também carregam a chave pública, propagando-a por toda a
    // mesh). É esta chave — a do deviceId real da outra ponta da conversa,
    // não a de quem fisicamente entregou o pacote — que deve ser usada para
    // que a cifra seja de fato ponta-a-ponta, independente de quantos
    // saltos (relays) a mensagem atravessa no caminho.
    private val devicePublicKeys = ConcurrentHashMap<String, PublicKey>()
    // endpointId direto -> deviceId lógico do vizinho (preenchido pelo HELLO)
    private val endpointToDeviceId = ConcurrentHashMap<String, String>()
    // deviceId lógico -> endpointId direto por onde ele é alcançável (próximo salto).
    // Preenchido tanto por HELLO (vizinhos diretos) quanto por anúncios de
    // presença repassados pela mesh (nós indiretos, multi-hop).
    private val routeTable = ConcurrentHashMap<String, String>()
    // deviceId lógico -> timestamp do último anúncio de presença recebido,
    // usado para expirar nós indiretos que somem da mesh sem aviso.
    private val lastPresenceSeenAt = ConcurrentHashMap<String, Long>()
    // IDs de mensagem já processadas, para evitar loops de flooding
    private val pendingConnections = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenMessageIds = LinkedHashSetSync<String>(MAX_SEEN_IDS)

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var presenceJob: Job? = null

    private val _status = MutableStateFlow(MeshStatus.IDLE)
    val status: StateFlow<MeshStatus> = _status

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val meuLogger by lazy { LoggerApp(context) }
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
        presenceJob = managerScope.launch {
            while (isActive) {
                broadcastPresence()
                pruneStalePresenceNodes()
                delay(PRESENCE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        presenceJob?.cancel()
        presenceJob = null
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointToDeviceId.clear()
        routeTable.clear()
        lastPresenceSeenAt.clear()
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
        ).addOnFailureListener { e ->
            meuLogger.gravarLog("[FAIL]", "Falha ao iniciar advertising")
            Log.w(TAG, "Falha ao iniciar advertising", e) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        meuLogger.gravarLog("MeshNetworkManager", "A descoberta foi finalizada.")
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnFailureListener { e ->
            meuLogger.gravarLog("[FAIL]", "Falha ao iniciar discovery")
            Log.w(TAG, "Falha ao iniciar discovery", e) }
    }

    // ---------------------------------------------------------------------
    // Descoberta de endpoints
    // ---------------------------------------------------------------------

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connectedEndpoints.containsKey(endpointId) || !pendingConnections.add(endpointId)) {
                return
            }
            connectionsClient.requestConnection(
                localDisplayName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                pendingConnections.remove(endpointId)
                meuLogger.gravarLog("[FAIL]", "Falha ao solicitar conexão com $endpointId")
                Log.w(TAG, "Falha ao solicitar conexão com $endpointId", e) }
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
            pendingConnections.add(endpointId)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    meuLogger.gravarLog("[FAIL]", "Falha ao aceitar conexão com $endpointId.")
                    Log.w(TAG, "Falha ao aceitar conexão com $endpointId", e) }
            upsertNode(endpointId, info.endpointName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTING, deviceId = null)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnections.remove(endpointId)
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints[endpointId] = connectedEndpoints[endpointId] ?: endpointId
                upsertNode(endpointId, connectedEndpoints[endpointId] ?: endpointId, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = null)
                sendHello(endpointId)
            } else {
                //connectedEndpoints.remove(endpointId)
                //removeNode(endpointId)
                handleEndpointGone(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            //connectedEndpoints.remove(endpointId)
            //endpointToDeviceId.remove(endpointId)
            //routeTable.entries.removeAll { it.value == endpointId }
            //removeNode(endpointId)
            handleEndpointGone(endpointId)
        }
    }
    /**
     * Limpa todo o estado associado a um endpoint que não está mais
     * disponível — seja por desconexão normal, falha de negociação ou falha
     * ao enviar um payload para ele. Centralizar essa limpeza evita que
     * entradas "fantasma" continuem na tabela de rotas ou na lista de nós
     * apontando para um link que já não existe.
     */
    private fun handleEndpointGone(endpointId: String) {
        connectedEndpoints.remove(endpointId)
        endpointToDeviceId.remove(endpointId)
        pendingConnections.remove(endpointId)
        routeTable.entries.removeAll { it.value == endpointId }
        removeNode(endpointId)
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
            meuLogger.gravarLog("[WARN]", "Nenhuma rota conhecida para $targetDeviceId.")
            return
        }
        // Importante: a chave usada para cifrar é a do DESTINO FINAL
        // (targetDeviceId), não a de nextHopEndpoint. nextHopEndpoint só diz
        // por qual vizinho físico o pacote deve sair primeiro; se o destino
        // estiver a mais de um salto, nextHopEndpoint é apenas um relay, e
        // usar a chave dele quebraria a cifra ponta-a-ponta (o relay não
        // consegue e não deve conseguir decifrar a mensagem).
        val peerKey = devicePublicKeys[targetDeviceId]
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

    /**
     * Anuncia (e propaga, via flooding com TTL) a presença deste dispositivo
     * para toda a mesh. É este mecanismo — e não o HELLO, que só é trocado
     * entre vizinhos diretos — que permite a nós a 2+ saltos de distância
     * descobrirem uns aos outros e aparecerem na lista de nós com
     * `isDirect = false` e `hopCount > 0`. Chamado periodicamente enquanto a
     * mesh está ativa, e também de forma oportunista sempre que um novo
     * vizinho direto é estabelecido.
     */
    private fun broadcastPresence() {
        if (connectedEndpoints.isEmpty()) return
        val messageId = UUID.randomUUID().toString()
        val json = JSONObject().apply {
            put("type", TYPE_PRESENCE)
            put("id", messageId)
            put("originId", localDeviceId)
            put("originName", localDisplayName)
            put("publicKey", cryptoManager.publicKeyBase64)
            put("hopCount", 0)
            put("ttl", DEFAULT_TTL)
        }
        seenMessageIds.add(messageId)
        broadcastToAllDirectPeers(json, excludeEndpointId = null)
    }

    private fun sendPayloadTo(endpointId: String, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { e ->
                meuLogger.gravarLog("[FAIL]", "Falha ao enviar payload para $endpointId")
                Log.w(TAG, "Falha ao enviar payload para $endpointId", e)
                handleEndpointGone(endpointId)
            }
    }

    private fun broadcastToAllDirectPeers(json: JSONObject, excludeEndpointId: String?) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        val targets = connectedEndpoints.keys.filter { it != excludeEndpointId }
        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, payload)
                .addOnFailureListener { e ->
                    meuLogger.gravarLog("[FAIL]", "Falha ao enviar payload em broadcast para vizinhos diretos")
                    Log.w(TAG, "Falha ao enviar payload em broadcast para vizinhos diretos", e)
                }
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
                meuLogger.gravarLog("[ERROR]", "Payload malformado recebido de $fromEndpointId")
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
            TYPE_PRESENCE -> handlePresence(fromEndpointId, json)
        }
    }

    private fun handleHello(fromEndpointId: String, json: JSONObject) {
        val deviceId = json.optString("deviceId")
        val displayName = json.optString("displayName")
        val publicKeyB64 = json.optString("publicKey")

        connectedEndpoints[fromEndpointId] = displayName
        endpointToDeviceId[fromEndpointId] = deviceId
        routeTable[deviceId] = fromEndpointId
        try {
            devicePublicKeys[deviceId] = cryptoManager.decodePublicKey(publicKeyB64)
        } catch (e: Exception) {
            Log.w(TAG, "Chave pública inválida recebida de $displayName", e)
            meuLogger.gravarLog("[ERROR]", "Chave pública inválida recebida de $displayName.")
        }
        // Um novo nó indireto pode ter se tornado direto (ou vice-versa em
        // outra parte da mesh); remove qualquer entrada indireta obsoleta
        // para este deviceId e mantém apenas a direta, mais precisa.
        lastPresenceSeenAt.remove(deviceId)
        upsertNode(fromEndpointId, displayName, isDirect = true, hopCount = 0, status = NodeStatus.CONNECTED, deviceId = deviceId)

        // Anuncia minha presença imediatamente para que o resto da mesh
        // aprenda sobre este novo vizinho sem esperar o próximo tick
        // periódico, e vice-versa (o vizinho fará o mesmo ao me processar).
        broadcastPresence()
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

        if (targetId == localDeviceId) {
            val originId = json.optString("originId")
            val originName = json.optString("originName")
            val ts = json.optLong("ts")
            val encrypted = json.optBoolean("encrypted", false)
            val rawBody = json.optString("body")

            val body = if (encrypted) {
                // Decifra usando a chave pública do REMETENTE ORIGINAL
                // (originId), simetricamente ao que sendDirect faz ao
                // cifrar com a chave do destinatário final. Usar a chave de
                // fromEndpointId (quem entregou fisicamente o pacote) só
                // funcionaria para o caso de 1 salto; para mensagens
                // repassadas por relays, fromEndpointId seria o último
                // relay, não o remetente original, e a cifra nunca bateria.
                val originKey = devicePublicKeys[originId]
                if (originKey != null) {
                    try {
                        val secret = cryptoManager.deriveSharedSecret(originKey)
                        cryptoManager.decrypt(rawBody, secret)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao decifrar mensagem direta de $originName", e)
                        meuLogger.gravarLog("[ERROR]", "Falha ao decifrar mensagem direta de $originName.")
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

    /**
     * Processa um anúncio de presença recebido de outro nó da mesh (próprio
     * ou repassado por um relay). É o que preenche a lista de nós com
     * dispositivos fora do alcance direto e ensina a `routeTable` como
     * alcançá-los (próximo salto = `fromEndpointId`, o vizinho direto que
     * acabou de me entregar este anúncio).
     */
    private fun handlePresence(fromEndpointId: String, json: JSONObject) {
        val messageId = json.optString("id")
        if (!seenMessageIds.add(messageId)) return // já processada, evita loop

        val originId = json.optString("originId")
        val originName = json.optString("originName")
        val publicKeyB64 = json.optString("publicKey")
        val hopCount = json.optInt("hopCount", 0)
        val ttl = json.optInt("ttl", 0)

        if (originId != localDeviceId) {
            // Guarda a chave pública do originador sempre que ela vier
            // presente, independente de ele ser direto ou indireto — é o
            // que permite cifrar mensagens diretas ponta-a-ponta para
            // qualquer dispositivo da mesh, mesmo a vários saltos de
            // distância, sem depender de uma conexão HELLO direta com ele.
            if (publicKeyB64.isNotEmpty() && !devicePublicKeys.containsKey(originId)) {
                try {
                    devicePublicKeys[originId] = cryptoManager.decodePublicKey(publicKeyB64)
                } catch (e: Exception) {
                    meuLogger.gravarLog("[ERROR]", "Chave pública inválida em anúncio de presença de $originName.")
                    Log.w(TAG, "Chave pública inválida em anúncio de presença de $originName.", e)
                }
            }

            if (!isDirectNeighborDevice(originId)) {
                // fromEndpointId é um vizinho direto meu que me repassou este
                // anúncio; ele é, portanto, um next-hop válido para alcançar
                // originId. hopCount reflete quantos saltos além da conexão
                // direta já foram percorridos até aqui.
                routeTable[originId] = fromEndpointId
                lastPresenceSeenAt[originId] = System.currentTimeMillis()
                upsertNode(
                    endpointId = "mesh:$originId",
                    displayName = originName,
                    isDirect = false,
                    hopCount = hopCount,
                    status = NodeStatus.CONNECTED,
                    deviceId = originId
                )
            }
        }

        if (ttl > 0) {
            val forwarded = JSONObject(json.toString()).apply {
                put("hopCount", hopCount + 1)
                put("ttl", ttl - 1)
            }
            broadcastToAllDirectPeers(forwarded, excludeEndpointId = fromEndpointId)
        }
    }

    private fun isDirectNeighborDevice(deviceId: String): Boolean =
        endpointToDeviceId.containsValue(deviceId)

    /**
     * Remove da lista nós indiretos que pararam de ser renovados por novos
     * anúncios de presença — sinal de que a mesh mudou de topologia em
     * algum ponto fora do meu alcance direto e eles não são mais
     * alcançáveis. Nós diretos não são afetados: sua remoção é feita
     * imediatamente por `onDisconnected`.
     */
    private fun pruneStalePresenceNodes() {
        val now = System.currentTimeMillis()
        val stale = lastPresenceSeenAt.filterValues { now - it > PRESENCE_NODE_TIMEOUT_MS }.keys
        if (stale.isEmpty()) return
        stale.forEach { deviceId ->
            lastPresenceSeenAt.remove(deviceId)
            routeTable.remove(deviceId)
        }
        _nodes.update { current -> current.filterNot { !it.isDirect && it.deviceId in stale } }
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
        // O nó é indexado pelo endpointId — físico da Nearby Connections para
        // vizinhos diretos, ou sintético ("mesh:<deviceId>") para nós
        // aprendidos apenas via anúncio de presença (indiretos). Quando o
        // deviceId lógico é conhecido, também deduplicamos por ele, pois o
        // mesmo dispositivo pode ter passado de indireto para direto (ou
        // vice-versa) e não deve aparecer duas vezes na lista.
        _nodes.update { current ->
            if (deviceId != null && !isDirect) {
                // Um anúncio indireto nunca deve sobrescrever uma conexão
                // direta já conhecida para o mesmo dispositivo: a direta é
                // sempre mais precisa e atualizada por outro caminho (HELLO).
                val existingDirect = current.firstOrNull { it.deviceId == deviceId && it.isDirect }
                if (existingDirect != null) return@update current
            }
            var filtered = current.filterNot { it.endpointId == endpointId }
            if (deviceId != null) {
                filtered = filtered.filterNot { it.deviceId == deviceId }
            }
            val previousDeviceId = deviceId ?: current.firstOrNull { it.endpointId == endpointId }?.deviceId
            filtered + Node(
                endpointId = endpointId,
                deviceId = previousDeviceId,
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
