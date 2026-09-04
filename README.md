# MeshChat

App Android de chat P2P descentralizado, sem servidor e sem internet, usando
a **Nearby Connections API** do Google Play Services em topologia **mesh**
(`Strategy.P2P_CLUSTER`).

Namespace / applicationId: `br.com.netscript.meshchat`.

## Como abrir e compilar

1. Abra a pasta `meshchat/` no Android Studio (Koala ou mais recente) como
   projeto existente.
2. Deixe o Gradle Sync baixar as dependências (precisa de internet e conta
   Google Play Services configurada no dispositivo/emulador de teste — o
   Nearby Connections **não funciona no emulador padrão**, use um
   dispositivo físico real, de preferência dois, para testar o P2P).
3. Rode em pelo menos dois dispositivos físicos com Bluetooth e Wi-Fi
   ligados e próximos um do outro.
4. Na primeira execução, aceite as permissões de localização e
   Bluetooth/Wi-Fi Nearby solicitadas — são exigidas pela Nearby
   Connections API para descoberta de dispositivos.

## Arquitetura

```
MainActivity
 └─ BottomNavigationView
     ├─ NodesFragment      → lista de nós conectados (diretos e via mesh)
     │     └─ toque em um nó → DirectChatFragment (chat 1-a-1, empilhado no back stack)
     ├─ MessagesFragment   → histórico de mensagens de broadcast (todas as mensagens
     │                       enviadas para "todos" na mesh)
     └─ SettingsFragment   → nome de usuário, persistido via DataStore
```

- **Chat exclusivo por nó**: tocar em um nó na lista (só habilitado quando ele
  está `CONNECTED` e seu `deviceId` lógico já é conhecido via HELLO) abre
  `DirectChatFragment`, empilhado sobre o container de fragments via
  `addToBackStack`. Ele filtra o `StateFlow<List<ChatMessage>>` global por
  `conversationId == peerDeviceId` (preenchido tanto para mensagens diretas
  enviadas quanto recebidas) e envia novas mensagens com
  `MeshNetworkManager.sendDirect(peerDeviceId, texto)`, cifradas ponta-a-ponta.
  O tab "Mensagens" da bottom bar mostra apenas broadcasts, para não duplicar
  o conteúdo dos chats diretos.

- **MeshNetworkManager** (`network/`): encapsula toda a lógica da Nearby
  Connections — advertising, discovery, ciclo de vida de conexão, e o
  protocolo de aplicação sobre payloads de bytes (JSON simples).
- **Roteamento mesh**: a Nearby Connections só entrega pacotes a vizinhos
  diretamente conectados. O alcance multi-hop é obtido por **flooding
  controlado**: cada mensagem tem um `id` único e um `ttl`; ao receber uma
  mensagem nova (id nunca visto), o nó repassa para todos os vizinhos
  diretos exceto quem enviou, decrementando o `ttl`, até chegar a zero ou o
  destino ser alcançado. Um `LinkedHashSet` com limite de tamanho
  deduplica IDs já vistos para evitar loops.
- **Criptografia** (`crypto/CryptoManager.kt`): ao conectar, os dispositivos
  trocam chaves públicas EC (curva P-256) via mensagem `HELLO`. Mensagens
  diretas (unicast) são cifradas com AES-256-GCM usando uma chave derivada
  por **ECDH**. Mensagens de broadcast trafegam em texto claro (não há um
  único destinatário para negociar chave).
- **Persistência**: `UserPreferences` (`data/UserPreferences.kt`) usa
  Jetpack DataStore (Preferences) para guardar o nome de usuário e um
  `deviceId` estável gerado uma única vez.
- Estado observável (`nodes`, `messages`, `status`) é exposto via
  `StateFlow` e coletado pelos fragments com `repeatOnLifecycle`.

## Limitações conhecidas / próximos passos

- O aceite de conexão é automático (`acceptConnection` sem confirmação de
  código); para produção, exiba o código de pareamento e peça confirmação
  manual em ambos os lados.
- O roteamento é flooding puro, não uma tabela de rotas otimizada — funciona
  bem em redes pequenas/médias, mas gera tráfego redundante em meshes
  grandes. Um próximo passo natural é implementar algo como um protocolo
  tipo AODV simplificado.
- Mensagens diretas assumem que o próximo salto conhecido em `routeTable`
  ainda é válido; não há retry nem fallback automático de rota se o vizinho
  cair no meio do caminho.
- Sem histórico persistente: mensagens e nós existem apenas em memória
  (StateFlow) enquanto o processo do app estiver vivo.
