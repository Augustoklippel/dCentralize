package br.com.netscript.meshchat.ui

sealed interface TransferState {
    object Idle : TransferState
    object DiscoveringOrAdvertising : TransferState
    object Connecting : TransferState
    data class Transferring(val progress: Int, val fileName: String) : TransferState
    data class Success(val fileName: String) : TransferState
    data class Error(val message: String) : TransferState
}