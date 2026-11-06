package com.gmdveast.financeflux.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationValidationTest {
    @Test
    fun validExpenseRoundTripsAsSchemaTwo() {
        val event = expenseEvent()
        assertTrue(OperationValidation.validate(event).isEmpty())

        val encoded = FinanceJson.encodeEvent(event)
        assertTrue(encoded.contains("\"schema\":2"))
        assertTrue(encoded.contains("\"type\":\"Расход\""))
        assertEquals(event, FinanceJson.decodeEvent(encoded))
    }

    @Test
    fun invalidCalendarDatesAndExcessPrecisionAreRejected() {
        assertFalse(OperationValidation.validDate("2026-02-29"))
        assertTrue(OperationValidation.validDate("2024-02-29"))
        assertFalse(OperationValidation.validDateTime("2026-11-05T25:00:00Z"))
        assertTrue(OperationValidation.validDateTime("1900-01-01T00:00:00Z"))
        assertTrue(OperationValidation.validDateTime("1999-12-31T23:59:59+14:00"))

        val tooPrecise = expenseEvent().copy(operation = expenseEvent().operation.copy(amount = 1.0000001))
        assertTrue(OperationValidation.validate(tooPrecise).any { it.contains("amount") })
    }

    @Test
    fun strictEventParserRejectsUnknownSchemaTwoFields() {
        val encoded = FinanceJson.encodeEvent(expenseEvent()).dropLast(1) + ",\"unknown\":true}"
        assertTrue(runCatching { FinanceJson.decodeEvent(encoded) }.isFailure)
    }

    @Test
    fun candidateUsesProfileAccountAndMatchingDefaultCategory() {
        val lists = references()
        val profile = ConnectorProfile(
            id = "profile-one",
            connectorId = "georgian-bank.sms",
            packageName = "com.example.testconnector",
            name = "Bank card",
            accountId = 6,
            defaultExpenseCategory = "Продукты",
            enabled = true,
        )
        val candidate = ConnectorCandidate("source-123", "2026-11-05", OperationType.EXPENSE, 24.5, "GEL")
        val normalized = CandidateNormalizer(lists).toEvent(candidate, profile, "event-12345678", "2026-11-05T12:00:00Z")
        assertEquals(6, normalized.operation.accountId)
        assertEquals("Продукты", normalized.operation.category)
        assertNull(normalized.operation.rate)
    }

    @Test
    fun invalidOrInactiveProfileMappingIsRejected() {
        val lists = references()
        val profile = ConnectorProfile(
            id = "profile-one",
            connectorId = "georgian-bank.sms",
            packageName = "com.example.testconnector",
            name = "Bank card",
            accountId = 50,
            defaultExpenseCategory = "Продукты",
            enabled = true,
        )
        val candidate = ConnectorCandidate("source-123", "2026-11-05", OperationType.EXPENSE, 24.5, "GEL")
        assertTrue(runCatching {
            CandidateNormalizer(lists).toEvent(candidate, profile, "event-12345678", "2026-11-05T12:00:00Z")
        }.isFailure)
    }

    @Test
    fun sameCurrencyTransferRequiresEqualAmountsAndDifferentActiveAccounts() {
        val lists = FinanceReferenceLists(
            currencies = listOf("GEL"),
            accounts = listOf(ReferenceAccount(6, "Bank A", "GEL"), ReferenceAccount(7, "Bank B", "GEL")),
        )
        val profile = ConnectorProfile(
            id = "profile-one",
            connectorId = "georgian-bank.sms",
            packageName = "com.example.testconnector",
            name = "Bank A",
            accountId = 6,
            enabled = true,
        )
        val candidate = ConnectorCandidate(
            candidateId = "source-123",
            date = "2026-11-05",
            type = OperationType.TRANSFER,
            amount = 10.0,
            currency = "GEL",
            destinationAccountId = 7,
            destinationAmount = 9.0,
        )
        assertTrue(runCatching {
            CandidateNormalizer(lists).toEvent(candidate, profile, "event-12345678", "2026-11-05T12:00:00Z")
        }.isFailure)
    }

    private fun expenseEvent() = AutomaticOperationEvent(
        schema = 2,
        id = "event-12345678",
        author = "finance-flux",
        createdAt = "2026-11-05T12:00:00Z",
        automation = AutomationOrigin("georgian-bank.sms", "profile-one"),
        operation = FinanceOperation(
            date = "2026-11-05",
            type = OperationType.EXPENSE,
            amount = 24.5,
            currency = "GEL",
            accountId = 6,
            category = "Продукты",
            rate = 31.5,
            notes = "Тестовая операция",
        ),
    )

    private fun references() = FinanceReferenceLists(
        categories = listOf(ReferenceCategory("Продукты", "Расход", "🛒")),
        currencies = listOf("GEL", "RUB"),
        accounts = listOf(ReferenceAccount(6, "Bank", "GEL")),
    )
}
