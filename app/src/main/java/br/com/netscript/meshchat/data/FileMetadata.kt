package br.com.netscript.meshchat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FileMetadata(
    val payloadId: Long, // ID que o Payload de arquivo terá
    val fileName: String,
    val fileSize: Long
)
