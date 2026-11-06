package com.gmdveast.financeflux.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.gmdveast.financeflux.core.FinanceJson
import com.gmdveast.financeflux.core.FinanceReferenceLists
import com.gmdveast.financeflux.core.DesktopProtocolCompatibility
import com.gmdveast.financeflux.core.DesktopProtocolCompatibilityCheck
import com.gmdveast.financeflux.core.DesktopProtocolProblem
import com.gmdveast.financeflux.core.ReferenceListsValidation
import com.gmdveast.financeflux.core.QueueStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafVaultStore(
    private val context: Context,
    private val treeUri: Uri,
) : QueueStorage {
    private val resolver: ContentResolver get() = context.contentResolver

    fun readReferenceLists(): FinanceReferenceLists? = runCatching {
        val config = child(root(), "config") ?: return null
        val file = child(config, "lists.json") ?: return null
        val text = readText(file, MAX_REFERENCE_LIST_BYTES)
        val envelope = FinanceJson.decodeReferenceLists(text)
        if (envelope.schema != 1) return null
        envelope.lists.takeIf { ReferenceListsValidation.validate(it).isEmpty() }
    }.getOrNull()

    fun readDesktopProtocolCompatibility(): DesktopProtocolCompatibility {
        val text = readFile("config/protocol.json", MAX_PROTOCOL_BYTES)
            ?: return DesktopProtocolCompatibility(DesktopProtocolProblem.MISSING)
        val protocol = runCatching { FinanceJson.decodeDesktopProtocol(text) }.getOrNull()
            ?: return DesktopProtocolCompatibility(DesktopProtocolProblem.INVALID)
        return DesktopProtocolCompatibilityCheck.validate(protocol)
    }

    fun vaultDisplayName(): String = root().name ?: "Finance Sync"

    fun queueCounts(): Pair<Int, Int> {
        val files = listFiles("incoming")
        return files.count { it.name?.endsWith(".json") == true } to
            files.count { it.name?.endsWith(".pending") == true }
    }

    fun deleteManagedFile(file: DocumentFile): Boolean = runCatching { file.delete() }.getOrDefault(false)

    override suspend fun readIncomingJson(eventId: String): String? = withContext(Dispatchers.IO) {
        findIncoming(eventId, ".json")?.let { readText(it, MAX_EVENT_BYTES) }
    }

    override suspend fun readPendingJson(eventId: String): String? = withContext(Dispatchers.IO) {
        findIncoming(eventId, ".pending")?.let { readText(it, MAX_EVENT_BYTES) }
    }

    override suspend fun writePendingJson(eventId: String, json: String) = withContext(Dispatchers.IO) {
        val folder = ensureFolder(root(), "incoming")
        val fileName = "$eventId.pending"
        val existing = child(folder, fileName)
        if (existing != null) {
            check(readText(existing, MAX_EVENT_BYTES) == json) { "pending event already has different content" }
            return@withContext
        }
        val pending = folder.createFile("application/json", fileName)
            ?: error("provider_refused_pending_file")
        writeAndClose(pending, json)
    }

    override suspend fun publishPendingJson(eventId: String) = withContext(Dispatchers.IO) {
        val folder = child(root(), "incoming") ?: error("incoming_folder_missing")
        val pending = child(folder, "$eventId.pending") ?: error("pending_file_missing")
        val final = child(folder, "$eventId.json")
        if (final != null) {
            check(readText(final, MAX_EVENT_BYTES) == readText(pending, MAX_EVENT_BYTES)) { "published event already has different content" }
            pending.delete()
            return@withContext
        }
        val renamed = DocumentsContract.renameDocument(resolver, pending.uri, "$eventId.json")
        check(renamed != null) { "provider_does_not_support_rename" }
        val published = DocumentFile.fromSingleUri(context, renamed)
        check(published?.name == "$eventId.json") { "provider_did_not_publish_json_name" }
    }

    override suspend fun writeMarkdownCard(fileName: String, contents: String) = withContext(Dispatchers.IO) {
        val folder = ensureFolder(root(), "operations")
        val existing = child(folder, fileName)
        if (existing != null) {
            check(readText(existing) == contents) { "managed_card_name_conflict" }
            return@withContext
        }
        val card = folder.createFile("text/markdown", fileName)
            ?: error("provider_refused_card_file")
        writeAndClose(card, contents)
    }

    fun readFile(relativePath: String, maxBytes: Int = MAX_REFERENCE_LIST_BYTES): String? = runCatching {
        var current = root()
        val segments = relativePath.split('/').filter(String::isNotBlank)
        for (segment in segments) {
            current = child(current, segment) ?: return null
        }
        if (!current.isFile) return null
        readText(current, maxBytes)
    }.getOrNull()

    fun listFiles(relativeFolder: String): List<DocumentFile> = runCatching {
        var current = root()
        for (segment in relativeFolder.split('/').filter(String::isNotBlank)) {
            current = child(current, segment) ?: return emptyList()
        }
        current.listFiles().filter(DocumentFile::isFile)
    }.getOrDefault(emptyList())

    fun renameCard(file: DocumentFile, newName: String): Boolean = runCatching {
        DocumentsContract.renameDocument(resolver, file.uri, newName) != null
    }.getOrDefault(false)

    private fun findIncoming(eventId: String, suffix: String): DocumentFile? {
        val folder = child(root(), "incoming") ?: return null
        return child(folder, "$eventId$suffix")
    }

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("invalid_vault_tree_uri")

    private fun child(parent: DocumentFile, name: String): DocumentFile? = parent.findFile(name)

    private fun ensureFolder(parent: DocumentFile, name: String): DocumentFile {
        child(parent, name)?.let {
            check(it.isDirectory) { "expected_folder" }
            return it
        }
        return parent.createDirectory(name) ?: error("provider_refused_folder")
    }

    private fun readText(file: DocumentFile, maxBytes: Int = MAX_REFERENCE_LIST_BYTES): String {
        val input = resolver.openInputStream(file.uri) ?: error("provider_refused_read")
        val output = java.io.ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes) { "document_too_large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun writeAndClose(file: DocumentFile, contents: String) {
        resolver.openOutputStream(file.uri, "w")?.use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error("provider_refused_write")
    }

    companion object {
        private const val MAX_EVENT_BYTES = 64 * 1024
        private const val MAX_REFERENCE_LIST_BYTES = 256 * 1024
        private const val MAX_PROTOCOL_BYTES = 32 * 1024
    }
}
