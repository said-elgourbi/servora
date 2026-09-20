package com.servora.android.data.jobs

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.session.FakeAuthenticatedSubject

/**
 * The photo slice's collaborators, all in memory.
 *
 * The real [JobPhotoSession] is built over them, because what it does — recording a capture durably,
 * queueing the upload through the outbox, deleting local bytes only once the backend owns them — is
 * the behaviour under test. Only the pieces that need a device, a database or a network are replaced
 * (`qa.md` §6.1).
 */
class PhotoCollaborators(
    subjectId: String? = "user-1",
) {
    val outbox = InMemoryOutboxStore()
    val store = InMemoryPendingJobPhotoStore(outbox)
    val files = FakeJobPhotoFiles()
    val processing = FakeJobPhotoProcessing()
    val subject = FakeAuthenticatedSubject(subjectId)
    val sync = FakeOfflineSync()

    val session = JobPhotoSession(
        pending = store,
        files = files,
        processing = processing,
        outbox = outbox,
        subject = subject,
        offlineSync = sync,
        clock = TEST_CLOCK,
    )
}
