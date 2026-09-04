package br.com.netscript.meshchat.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia ponta-a-ponta para mensagens diretas na mesh.
 *
 * Cada dispositivo gera um par de chaves EC (curva P-256) na inicialização.
 * Ao conectar com um novo nó, as chaves públicas são trocadas em texto claro
 * (elas não são segredo) e cada lado deriva um segredo compartilhado via
 * ECDH, usado como chave AES-256 para cifrar mensagens com AES-GCM.
 *
 * Mensagens de broadcast (para toda a mesh) não são cifradas com este
 * esquema pois não há um destinatário único com quem negociar uma chave;
 * apenas mensagens diretas (unicast) usam este canal.
 */
class CryptoManager {

    private val keyPair: KeyPair = generateKeyPair()

    /** Chave pública local, codificada em Base64, pronta para ser anunciada aos peers. */
    val publicKeyBase64: String
        get() = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256, SecureRandom())
        return generator.generateKeyPair()
    }

    /** Decodifica a chave pública Base64 recebida de um peer. */
    fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /** Deriva o segredo compartilhado (chave AES-256) via ECDH com a chave pública de um peer. */
    fun deriveSharedSecret(peerPublicKey: PublicKey): SecretKeySpec {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        // Reduz o segredo bruto do ECDH para 256 bits usando SHA-256 como KDF simples.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(digest, "AES")
    }

    /** Cifra [plaintext] com AES-256-GCM usando [key]. Retorna "ivBase64:cipherBase64". */
    fun encrypt(plaintext: String, key: SecretKeySpec): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
    }

    /** Decifra uma string no formato "ivBase64:cipherBase64" produzida por [encrypt]. */
    fun decrypt(payload: String, key: SecretKeySpec): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "Payload cifrado malformado" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }
}
