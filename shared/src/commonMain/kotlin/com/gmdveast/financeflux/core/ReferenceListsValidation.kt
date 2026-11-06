package com.gmdveast.financeflux.core

object ReferenceListsValidation {
    private val currency = Regex("^[A-Z]{3}$")

    fun validate(lists: FinanceReferenceLists): List<String> = buildList {
        if (lists.accounts.isEmpty() || lists.accounts.size > 100) add("accounts are missing or exceed the limit")
        if (lists.accounts.map { it.id }.distinct().size != lists.accounts.size) add("account IDs are not unique")
        if (lists.accounts.any { it.id !in 5..104 || it.name.isBlank() || it.name.length > 100 || !currency.matches(it.currency) }) {
            add("an account entry is invalid")
        }
        if (lists.currencies.size > 200 || lists.currencies.any { !currency.matches(it) } || lists.currencies.distinct().size != lists.currencies.size) {
            add("currency list is invalid")
        }
        if (lists.currencies.isNotEmpty() && lists.accounts.any { it.currency !in lists.currencies }) {
            add("an account currency is absent from the currency list")
        }
        if (lists.categories.size > 200 || lists.categories.any {
                it.name.isBlank() || it.name.length > 100 || it.type !in setOf("Доход", "Расход") || (it.emoji?.length ?: 0) > 16
            }) {
            add("category list is invalid")
        }
        if (lists.necessities.size > 200 || lists.necessities.any { it.isBlank() || it.length > 100 }) {
            add("necessity list is invalid")
        }
    }
}
