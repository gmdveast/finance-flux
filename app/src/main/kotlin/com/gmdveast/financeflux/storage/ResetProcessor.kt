package com.gmdveast.financeflux.storage

import androidx.documentfile.provider.DocumentFile
import com.gmdveast.financeflux.core.FinanceJson
import com.gmdveast.financeflux.core.OperationValidation
import com.gmdveast.financeflux.core.ResetSnapshot
import java.time.OffsetDateTime

data class ResetOutcome(
    val snapshot: ResetSnapshot?,
    val deletedQueueFiles: Int,
    val deletedCards: Int,
)

class ResetProcessor(private val vault: SafVaultStore) {
    fun apply(): ResetOutcome {
        val raw = vault.readFile("config/reset.json", MAX_RESET_CHARS) ?: return ResetOutcome(null, 0, 0)
        if (raw.length > MAX_RESET_CHARS) return ResetOutcome(null, 0, 0)
        val snapshot = runCatching { FinanceJson.decodeReset(raw) }.getOrNull() ?: return ResetOutcome(null, 0, 0)
        if (!valid(snapshot)) return ResetOutcome(null, 0, 0)

        val cutoff = OffsetDateTime.parse(snapshot.cutoff).toInstant()
        val deletedIds = mutableSetOf<String>()
        var queueDeleted = 0
        for (file in vault.listFiles("incoming")) {
            val name = file.name ?: continue
            val suffix = when {
                name.endsWith(".json") -> ".json"
                name.endsWith(".pending") -> ".pending"
                else -> continue
            }
            val content = runCatching { vault.readFile("incoming/$name", 64 * 1024) }.getOrNull() ?: continue
            val event = runCatching { FinanceJson.decodeEvent(content) }.getOrNull() ?: continue
            if (event.id != name.removeSuffix(suffix)) continue
            if (OperationValidation.validate(event).isNotEmpty()) continue
            if (!isBeforeOrIncluded(event.id, event.createdAt, snapshot, cutoff)) continue
            if (vault.deleteManagedFile(file)) {
                deletedIds += event.id
                queueDeleted++
            }
        }

        var cardsDeleted = 0
        for (card in vault.listFiles("operations")) {
            val name = card.name ?: continue
            if (!isManagedQueueCard(name)) continue
            val markdown = runCatching { vault.readFile("operations/$name") }.getOrNull() ?: continue
            if (!Regex("(?m)^finance_capture:\\s*true\\s*$").containsMatchIn(markdown)) continue
            val id = frontmatter(markdown, "finance_id") ?: continue
            if (!validEventId(id) || !name.endsWith(" — ${id.takeLast(8)}.md")) continue
            if (frontmatter(markdown, "finance_json") != "incoming/$id.json") continue
            val createdAt = frontmatter(markdown, "created_at") ?: continue
            if (!OperationValidation.validDateTime(createdAt)) continue
            if (!isBeforeOrIncluded(id, createdAt, snapshot, cutoff)) continue
            if (id !in deletedIds && id !in snapshot.ids) continue
            if (vault.deleteManagedFile(card)) cardsDeleted++
        }
        return ResetOutcome(snapshot, queueDeleted, cardsDeleted)
    }

    fun shouldDiscard(eventId: String, createdAt: String, snapshot: ResetSnapshot?): Boolean {
        if (snapshot == null || snapshot.schema != 1 || !validEventId(eventId) ||
            !OperationValidation.validDateTime(snapshot.cutoff) || !OperationValidation.validDateTime(createdAt)) return false
        val cutoff = runCatching { OffsetDateTime.parse(snapshot.cutoff).toInstant() }.getOrNull() ?: return false
        return isBeforeOrIncluded(eventId, createdAt, snapshot, cutoff)
    }

    private fun isBeforeOrIncluded(id: String, createdAt: String, snapshot: ResetSnapshot, cutoff: java.time.Instant): Boolean =
        id in snapshot.ids || runCatching { OffsetDateTime.parse(createdAt).toInstant() <= cutoff }.getOrDefault(false)

    private fun valid(snapshot: ResetSnapshot): Boolean = snapshot.schema == 1 &&
        OperationValidation.validDateTime(snapshot.cutoff) &&
        snapshot.ids.size <= MAX_RESET_IDS &&
        snapshot.ids.all(::validResetId) && snapshot.ids.toSet().size == snapshot.ids.size

    private fun isManagedQueueCard(name: String): Boolean =
        (name.startsWith("1 — В очереди — ") || name.startsWith("2 — Архив — ") ||
            name.startsWith("1 — Pending — ") || name.startsWith("2 — Archived — ")) && name.endsWith(".md")

    private fun frontmatter(markdown: String, key: String): String? {
        val block = Regex("(?s)^---\\s*\\n(.*?)\\n---").find(markdown)?.groupValues?.getOrNull(1) ?: return null
        val raw = Regex("(?m)^${Regex.escape(key)}:\\s*(.*?)\\s*$").find(block)?.groupValues?.getOrNull(1) ?: return null
        return raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun validEventId(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{5,127}$").matches(value)

    private fun validResetId(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{5,127}$").matches(value)

    companion object {
        private const val MAX_RESET_CHARS = 1_000_000
        private const val MAX_RESET_IDS = 100_000
    }
}
