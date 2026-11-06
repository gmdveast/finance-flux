package com.gmdveast.financeflux.core

import kotlin.math.abs
import kotlin.math.round

object OperationValidation {
    private val identifier = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{5,127}$")
    private val shortIdentifier = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val currencyCode = Regex("^[A-Z]{3}$")
    private val datePattern = Regex("^(19\\d{2}|[2-9]\\d{3})-(\\d{2})-(\\d{2})$")
    private val dateTimePattern = Regex("^((?:19\\d{2}|[2-9]\\d{3})-\\d{2}-\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{1,9})?(Z|[+-](?:(?:0\\d|1[0-3]):[0-5]\\d|14:00))$")

    fun validate(event: AutomaticOperationEvent): List<String> = buildList {
        if (event.schema != 2) add("schema must be 2")
        if (!identifier.matches(event.id)) add("id is invalid")
        if (event.author.isBlank() || event.author.length > 80) add("author is invalid")
        if (!validDateTime(event.createdAt)) add("createdAt is invalid")
        if (!shortIdentifier.matches(event.automation.connectorId)) add("automation.connectorId is invalid")
        if (!shortIdentifier.matches(event.automation.profileId)) add("automation.profileId is invalid")
        validateOperation(event.operation).forEach(::add)
    }

    fun validateOperation(operation: FinanceOperation): List<String> = buildList {
        if (!validDate(operation.date)) add("operation.date is invalid")
        if (!operation.amount.isFinite() || operation.amount <= 0.0 || operation.amount > 100_000_000.0 || !hasAtMostSixDecimals(operation.amount)) {
            add("operation.amount is invalid")
        }
        if (!currencyCode.matches(operation.currency)) add("operation.currency is invalid")
        if (operation.accountId !in 5..104) add("operation.accountId is invalid")
        if (operation.rate != null && (!operation.rate.isFinite() || operation.rate < 1e-12 || operation.rate > 1_000_000.0)) {
            add("operation.rate is invalid")
        }
        if (operation.currency == "RUB" && operation.rate != null && operation.rate != 1.0) add("RUB rate must be 1")
        if (operation.category != null && operation.category.length > 100) add("operation.category is too long")
        if (operation.necessity != null && operation.necessity.length > 100) add("operation.necessity is too long")
        if (operation.notes != null && operation.notes.length > 500) add("operation.notes is too long")

        if (operation.type == OperationType.TRANSFER) {
            val destinationId = operation.destinationAccountId
            val destinationAmount = operation.destinationAmount
            if (destinationId == null || destinationId !in 5..104 || destinationId == operation.accountId) {
                add("transfer destination account is invalid")
            }
            if (destinationAmount == null || !destinationAmount.isFinite() || destinationAmount <= 0.0 || destinationAmount > 100_000_000.0 || !hasAtMostSixDecimals(destinationAmount)) {
                add("transfer destination amount is invalid")
            }
        } else {
            if (operation.category.isNullOrBlank()) add("income and expense require a category")
            if (operation.destinationAccountId != null) add("non-transfer cannot have a destination account")
            if (operation.destinationAmount != null) add("non-transfer cannot have a destination amount")
        }
    }

    fun validDate(value: String): Boolean {
        val match = datePattern.matchEntire(value) ?: return false
        return validCalendarDate(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }

    fun validDateTime(value: String): Boolean {
        val match = dateTimePattern.matchEntire(value) ?: return false
        if (!validDate(match.groupValues[1])) return false
        val hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].toInt()
        val second = match.groupValues[4].toInt()
        return hour in 0..23 && minute in 0..59 && second in 0..59
    }

    private fun validCalendarDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in 1900..9999 || month !in 1..12) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..days
    }

    private fun hasAtMostSixDecimals(value: Double): Boolean {
        if (!value.isFinite()) return false
        val scaled = value * 1_000_000.0
        return abs(scaled - round(scaled)) < 0.0001
    }
}

class CandidateNormalizer(private val lists: FinanceReferenceLists) {
    fun toEvent(
        candidate: ConnectorCandidate,
        profile: ConnectorProfile,
        eventId: String,
        createdAt: String,
    ): AutomaticOperationEvent {
        require(profile.enabled) { "profile is disabled" }
        require(shortIdentifier(profile.connectorId)) { "profile has an invalid connector ID" }
        require(shortIdentifier(profile.id)) { "profile has an invalid ID" }
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{5,127}$").matches(candidate.candidateId)) { "candidateId is invalid" }

        val account = lists.accounts.firstOrNull { it.id == profile.accountId }
            ?: throw IllegalArgumentException("profile account is not an active account in config/lists.json")
        require(account.currency == candidate.currency) { "candidate currency does not match the selected account" }
        require(lists.currencies.isEmpty() || candidate.currency in lists.currencies) { "candidate currency is absent from config/lists.json" }

        val category = when (candidate.type) {
            OperationType.EXPENSE -> profile.defaultExpenseCategory
            OperationType.INCOME -> profile.defaultIncomeCategory
            OperationType.TRANSFER -> null
        }
        if (candidate.type != OperationType.TRANSFER) {
            require(!category.isNullOrBlank()) { "profile has no default category for this operation type" }
            val expectedType = if (candidate.type == OperationType.EXPENSE) "Расход" else "Доход"
            require(lists.categories.any { it.name == category && it.type == expectedType }) {
                "profile category is absent from the matching reference list"
            }
        } else {
            val destinationId = candidate.destinationAccountId
                ?: throw IllegalArgumentException("transfer candidate has no destination account")
            val destination = lists.accounts.firstOrNull { it.id == destinationId }
                ?: throw IllegalArgumentException("transfer destination is not an active account")
            require(destination.id != account.id) { "transfer source and destination must differ" }
            if (destination.currency == account.currency) {
                require(candidate.destinationAmount == candidate.amount) { "same-currency transfer amounts must match" }
            }
        }

        val event = AutomaticOperationEvent(
            schema = 2,
            id = eventId,
            author = "finance-flux",
            createdAt = createdAt,
            automation = AutomationOrigin(profile.connectorId, profile.id),
            operation = FinanceOperation(
                date = candidate.date,
                type = candidate.type,
                amount = candidate.amount,
                currency = candidate.currency,
                accountId = account.id,
                category = category,
                rate = if (candidate.currency == "RUB") 1.0 else null,
                notes = candidate.notes,
                destinationAccountId = candidate.destinationAccountId,
                destinationAmount = candidate.destinationAmount,
            ),
        )
        val errors = OperationValidation.validate(event)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return event
    }

    private fun shortIdentifier(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(value)
}
