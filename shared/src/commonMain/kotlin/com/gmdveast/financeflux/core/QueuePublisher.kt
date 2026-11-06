package com.gmdveast.financeflux.core

interface QueueStorage {
    suspend fun readIncomingJson(eventId: String): String?
    suspend fun readPendingJson(eventId: String): String?
    suspend fun writePendingJson(eventId: String, json: String)
    suspend fun publishPendingJson(eventId: String)
    suspend fun writeMarkdownCard(fileName: String, contents: String)
}

data class QueuePublishResult(
    val queued: Boolean,
    val cardCreated: Boolean,
    val message: String,
)

class QueuePublisher(private val storage: QueueStorage) {
    suspend fun publish(event: AutomaticOperationEvent, lists: FinanceReferenceLists): QueuePublishResult {
        val validationErrors = OperationValidation.validate(event)
        if (validationErrors.isNotEmpty()) {
            return QueuePublishResult(false, false, validationErrors.joinToString("; "))
        }

        val json = FinanceJson.encodeEvent(event) + "\n"
        try {
            val published = storage.readIncomingJson(event.id)
            if (published != null) {
                check(normalizeJson(published) == normalizeJson(json)) { "event_id_conflict" }
            } else {
                val pending = storage.readPendingJson(event.id)
                if (pending != null) {
                    check(normalizeJson(pending) == normalizeJson(json)) { "pending_id_conflict" }
                } else {
                    storage.writePendingJson(event.id, json)
                }
                storage.publishPendingJson(event.id)
            }
        } catch (_: Exception) {
            return QueuePublishResult(false, false, "queue_publish_failed")
        }

        val cardCreated = try {
            storage.writeMarkdownCard(OperationCard.fileName(event, lists), OperationCard.render(event, lists))
            true
        } catch (_: Exception) {
            false
        }
        return QueuePublishResult(true, cardCreated, if (cardCreated) "queued" else "queued_without_card")
    }

    private fun normalizeJson(value: String): String = value.trim().replace("\r\n", "\n")
}

object OperationCard {
    fun fileName(event: AutomaticOperationEvent, lists: FinanceReferenceLists): String {
        val operation = event.operation
        val account = lists.accounts.firstOrNull { it.id == operation.accountId }?.name ?: "#${operation.accountId}"
        val category = operation.category?.let { name ->
            val emoji = lists.categories.firstOrNull { it.name == name }?.emoji.orEmpty()
            listOf(emoji, name).filter(String::isNotBlank).joinToString(" ")
        } ?: account
        val base = "1 — В очереди — ${operation.date} — $category — ${operation.amount} ${operation.currency}"
        return "${sanitize(base)} — ${event.id.takeLast(8)}.md"
    }

    fun render(event: AutomaticOperationEvent, lists: FinanceReferenceLists): String {
        val operation = event.operation
        val accountName = lists.accounts.firstOrNull { it.id == operation.accountId }?.name ?: "#${operation.accountId}"
        val destination = operation.destinationAccountId?.let { id -> lists.accounts.firstOrNull { it.id == id } }
        val destinationName = destination?.name ?: operation.destinationAccountId?.let { "#$it" }.orEmpty()
        val label = operation.category ?: if (operation.type == OperationType.TRANSFER) "$accountName → $destinationName" else accountName
        val title = when (operation.type) {
            OperationType.INCOME -> "Доход"
            OperationType.EXPENSE -> "Расход"
            OperationType.TRANSFER -> "Перевод"
        }
        val creditedCurrency = destination?.currency ?: operation.currency
        val jsonPath = "incoming/${event.id}.json"
        val transferRows = if (operation.type == OperationType.TRANSFER) {
            "| Счёт назначения | ${markdown(destinationName)} |\n" +
                "| Зачислено | ${markdown("${operation.destinationAmount} $creditedCurrency")} |\n"
        } else ""
        return """---
finance_capture: true
finance_id: "${yaml(event.id)}"
finance_json: "${yaml(jsonPath)}"
created_at: "${yaml(event.createdAt)}"
finance_category: "${yaml(operation.category.orEmpty())}"
finance_locale: "ru"
---

# $title: ${markdown(label)}

> [!info] Автоматически созданная операция
> Карточка только для чтения. Измените или удалите операцию в Finance после импорта.

| Поле | Значение |
| --- | --- |
| Дата | ${markdown(operation.date)} |
| Тип | $title |
| Сумма | ${markdown("${operation.amount} ${operation.currency}")} |
| Счёт | ${markdown(accountName)} |
$transferRows| Категория | ${markdown(operation.category.orEmpty())} |
| Заметка | ${markdown(operation.notes.orEmpty())} |
| ID | `${event.id}` |

"""
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[<>:\"/\\\\|?*]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(160)

    private fun yaml(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun markdown(value: String): String = value.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
}
