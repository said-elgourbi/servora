package com.servora.android.data.session

/** [AuthenticatedSubject] a test decides, so local state can be scoped without a stored token. */
class FakeAuthenticatedSubject(private var subjectId: String? = "user-1") : AuthenticatedSubject {

    override fun current(): String? = subjectId

    /** Changes the subject a later call reports; `null` models no session. */
    fun setSubject(subjectId: String?) {
        this.subjectId = subjectId
    }
}
