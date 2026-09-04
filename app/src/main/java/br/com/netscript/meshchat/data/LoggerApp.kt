package br.com.netscript.meshchat.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class LoggerApp (private val context: Context) {
    val nomeArquivo = "app_logger.log"
    // Limite máximo de tamanho do arquivo: 20 MB (em bytes)
    private val tamanhoMaximoBytes = 20 * 1024 * 1024

    fun gravarLog(tag: String, mensagem: String, throwable: Throwable? = null) {
        try {
            val arquivoLog = File(context.filesDir, nomeArquivo)

            // Controle de tamanho: Se passar do limite, limpa o arquivo anterior
            if (arquivoLog.exists() && arquivoLog.length() > tamanhoMaximoBytes) {
                arquivoLog.writeText("[LOGS REINICIADOS POR LIMITE DE TAMANHO]\n")
            }

            val formatoData = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.getDefault())
            val dataHoraAtual = formatoData.format(Date())
            val linhaLog = "$dataHoraAtual [$tag]: $mensagem\n"

            val fileWriter = FileWriter(arquivoLog, true)
            val printWriter = PrintWriter(fileWriter)

            printWriter.print(linhaLog)
            throwable?.printStackTrace(printWriter)

            printWriter.close()
            fileWriter.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}