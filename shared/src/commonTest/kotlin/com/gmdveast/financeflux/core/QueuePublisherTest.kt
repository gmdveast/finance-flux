package com.gmdveast.financeflux.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuePublisherTest {
    @Test
    fun publicationClosesPendingBeforeJsonAndCard() = runTest {
        val storage = MemoryQueueStorage()
        val result = QueuePublisher(storage).publish(event(), lists())

        assertTrue(result.queued)
        assertTrue(result.cardCreated)
        assertEquals(listOf("write-pending", "rename-to-json", "write-card"), storage.actions)
        assertTrue(storage.cards.values.single().contains("finance_id: \"event-12345678\""))
        assertTrue(storage.cards.keys.single().startsWith("1 — В очереди — "))
    }

    @Test
    fun cardFailureDoesNotRemoveAlreadyPublishedQueueEvent() = runTest {
        val storage = MemoryQueueStorage(failCards = true)
        val result = QueuePublisher(storage).publish(event(), lists())

        assertTrue(result.queued)
        assertFalse(result.cardCreated)
        assertTrue("event-12345678" in storage.incoming)
        assertEquals(listOf("write-pending", "rename-to-json", "write-card"), storage.actions)
    }

    @Test
    fun repeatedPublicationOfIdenticalEventIsIdempotent() = runTest {
        val storage = MemoryQueueStorage()
        val publisher = QueuePublisher(storage)
        val first = publisher.publish(event(), lists())
        val firstActions = storage.actions.toList()
        val second = publisher.publish(event(), lists())

        assertTrue(first.queued && second.queued)
        assertEquals(firstActions, storage.actions.take(firstActions.size))
        assertEquals(1, storage.incoming.size)
        assertEquals(1, storage.cards.size)
    }

    private fun event() = AutomaticOperationEvent(
        schema = 2,
        id = "event-12345678",
        author = "finance-flux",
        createdAt = "2026-11-05T12:00:00Z",
        automation = AutomationOrigin("georgian-bank.sms", "profile-one"),
        operation = FinanceOperation("2026-11-05", OperationType.EXPENSE, 24.5, "GEL", 6, "Продукты"),
    )

    private fun lists() = FinanceReferenceLists(
        categories = listOf(ReferenceCategory("Продукты", "Расход", "🛒")),
        currencies = listOf("GEL"),
        accounts = listOf(ReferenceAccount(6, "Bank", "GEL")),
    )

    private class MemoryQueueStorage(private val failCards: Boolean = false) : QueueStorage {
        val pending = mutableMapOf<String, String>()
        val incoming = mutableMapOf<String, String>()
        val cards = mutableMapOf<String, String>()
        val actions = mutableListOf<String>()

        override suspend fun readIncomingJson(eventId: String): String? = incoming[eventId]
        override suspend fun readPendingJson(eventId: String): String? = pending[eventId]
        override suspend fun writePendingJson(eventId: String, json: String) {
            actions += "write-pending"
            pending[eventId] = json
        }
        override suspend fun publishPendingJson(eventId: String) {
            actions += "rename-to-json"
            incoming[eventId] = pending.remove(eventId) ?: error("missing pending event")
        }
        override suspend fun writeMarkdownCard(fileName: String, contents: String) {
            actions += "write-card"
            if (failCards) error("provider failure")
            cards[fileName] = contents
        }
    }
}
