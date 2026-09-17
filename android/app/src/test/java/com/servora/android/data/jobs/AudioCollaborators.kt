package com.servora.android.data.jobs

import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.session.FakeAuthenticatedSubject
import java.time.Clock

/**
 * The audio slice's collaborators, all in memory.
 *
 * The real [JobAudioSession] is built over them, because what it does — recording a take durably,
 * queueing the upload through the outbox, deleting local bytes only once the backend owns them — is the
 * behaviour under test. Only the pieces that need a device, a database or a network are replaced
 * (`qa.md` §6.1); the microphone is one of them, which is why the recorder is a port.
 */
class AudioCollaborators(
    subjectId: String? = "user-1",
    clock: Clock = TEST_CLOCK,
) {
    val outbox = InMemoryOutboxStore()
    val store = InMemoryPendingJobAudioNoteStore(outbox)
    val files = FakeJobAudioFiles()
    val recorder = FakeJobAudioRecorder(files)
    val subject = FakeAuthenticatedSubject(subjectId)
    val sync = FakeOfflineSync()

    val session = JobAudioSession(
        pending = store,
        files = files,
        recorder = recorder,
        outbox = outbox,
        subject = subject,
        offlineSync = sync,
        clock = clock,
    )
}
