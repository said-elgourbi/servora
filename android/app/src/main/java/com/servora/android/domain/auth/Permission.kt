package com.servora.android.domain.auth

enum class Permission(val code: String, val aliases: Set<String> = emptySet()) {
    CUSTOMERS_VIEW("customers.view", aliases = setOf("view.customers", "CUSTOMER_VIEW")),
    CUSTOMERS_CREATE("customers.create", aliases = setOf("create.customers", "CUSTOMER_CREATE")),
    CUSTOMERS_EDIT("customers.edit", aliases = setOf("edit.customers", "CUSTOMER_UPDATE")),
    CUSTOMERS_ARCHIVE("customers.archive", aliases = setOf("archive.customers", "CUSTOMER_DELETE")),
    // Property capabilities are independent of the customer capabilities (`BR-085`); a Property
    // action is never inferred from a customer permission.
    PROPERTIES_VIEW("properties.view"),
    PROPERTIES_CREATE("properties.create"),
    PROPERTIES_EDIT("properties.edit"),
    PROPERTIES_ARCHIVE("properties.archive"),
    PROPERTIES_DELETE("properties.delete"),
}

class PermissionChecker(granted: Set<String>) {
    private val granted = granted.mapTo(mutableSetOf(), ::canonicalPermissionCode)

    fun has(permission: Permission): Boolean = permission.code in granted
}

fun Set<String>.asPermissionChecker(): PermissionChecker = PermissionChecker(this)

private fun canonicalPermissionCode(code: String): String =
    Permission.entries.firstOrNull { permission ->
        code == permission.code || code in permission.aliases
    }?.code ?: code
