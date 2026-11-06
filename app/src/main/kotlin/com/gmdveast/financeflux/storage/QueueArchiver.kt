package com.gmdveast.financeflux.storage

import androidx.documentfile.provider.DocumentFile

class QueueArchiver(private val vault: SafVaultStore) {
    fun archiveCardsForMissingQueueFiles(): Int {
        var archived = 0
        for (card in vault.listFiles("operations")) {
            val name = card.name ?: continue
            val prefix = pendingPrefix(name) ?: continue
            val contents = read(card) ?: continue
            if (!contents.contains(Regex("(?m)^finance_capture:\\s*true\\s*$"))) continue
            val id = frontmatter(contents, "finance_id") ?: continue
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]{5,127}$").matches(id)) continue
            val expectedPath = "incoming/$id.json"
            if (frontmatter(contents, "finance_json") != expectedPath) continue
            if (vault.readFile(expectedPath) != null) continue
            if (vault.readFile("incoming/$id.pending") != null) continue
            val archivedName = name.replaceFirst(prefix, archivePrefix(prefix))
            if (vault.renameCard(card, archivedName)) archived++
        }
        return archived
    }

    private fun pendingPrefix(name: String): String? = when {
        name.startsWith("1 — В очереди — ") -> "1 — В очереди — "
        name.startsWith("1 — Pending — ") -> "1 — Pending — "
        else -> null
    }

    private fun archivePrefix(prefix: String): String = if (prefix.contains("Pending")) "2 — Archived — " else "2 — Архив — "

    private fun frontmatter(markdown: String, key: String): String? {
        val block = Regex("(?s)^---\\s*\\n(.*?)\\n---").find(markdown)?.groupValues?.getOrNull(1) ?: return null
        val raw = Regex("(?m)^${Regex.escape(key)}:\\s*(.*?)\\s*$").find(block)?.groupValues?.getOrNull(1) ?: return null
        return raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun read(file: DocumentFile): String? = runCatching {
        vault.readFile("operations/${file.name}")
    }.getOrNull()
}
