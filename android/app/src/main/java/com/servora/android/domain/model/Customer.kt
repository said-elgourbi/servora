package com.servora.android.domain.model

/** Stable, machine-readable customer type code. */
enum class CustomerType {
    INDIVIDUAL,
    COMPANY,
}

/** Stable, machine-readable customer status code. */
enum class CustomerStatus {
    ACTIVE,
    INACTIVE,
}

/**
 * A customer of exactly one organization. [type] selects which subtype record
 * must accompany it: [CustomerIndividual] or [CustomerCompany].
 */
data class Customer(
    val id: String,
    val organizationId: String,
    val type: CustomerType,
    val displayName: String,
    val email: String?,
    val phone: String?,
    val billingEmail: String?,
    val billingPhone: String?,
    val notes: String?,
    val status: CustomerStatus,
    val createdAt: String,
    val updatedAt: String,
    /**
     * Current Properties linked to the customer, derived by the backend (`BR-050`). A relationship
     * that has ended is history, so it is not counted.
     */
    val propertyCount: Int,
    /** Jobs that belong to the customer, whatever their status (`BR-048`). */
    val jobCount: Int,
)

/** Subtype record for a customer of type [CustomerType.INDIVIDUAL]. */
data class CustomerIndividual(
    val customerId: String,
    val firstName: String,
    val lastName: String,
    /** ISO calendar date (`YYYY-MM-DD`) when known. */
    val dateOfBirth: String?,
)

/** Subtype record for a customer of type [CustomerType.COMPANY]. */
data class CustomerCompany(
    val customerId: String,
    val legalName: String,
    val businessName: String?,
    val taxNumber: String?,
)

/** An individual customer together with its required subtype record. */
data class IndividualCustomer(
    val customer: Customer,
    val individual: CustomerIndividual,
)

/** A company customer together with its required subtype record. */
data class CompanyCustomer(
    val customer: Customer,
    val company: CustomerCompany,
)
