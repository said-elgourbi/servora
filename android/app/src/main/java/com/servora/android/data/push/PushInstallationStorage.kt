package com.servora.android.data.push

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
// One atomic record avoids restoring proof, revision or binding from different writes.
data class PushInstallation(
    val id: String,
    val secret: String,
    val revision: Int = 0,
    val bindingId: String? = null,
    val sessionId: String? = null,
    val needsTargetReset: Boolean = true,
    val organizationId: String? = null,
) {
    companion object {
        fun fresh(): PushInstallation {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return PushInstallation(
                UUID.randomUUID().toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            )
        }
    }
}

interface PushInstallationStorage {
    fun read(): PushInstallation?
    fun write(installation: PushInstallation)
}

/** Installation proof is app-private and explicitly excluded from backup/transfer. No target stored. */
@Singleton
class FilePushInstallationStorage @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : PushInstallationStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push-installation.json"))

    @Synchronized
    override fun read(): PushInstallation? = try {
        file.openRead().use { json.decodeFromString<PushInstallation>(it.readBytes().decodeToString()) }
            .also {
                UUID.fromString(it.id)
                require(it.secret.matches(Regex("[A-Za-z0-9_-]{43}")))
                require(it.revision in 0..2147483646)
            }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        // Lost/corrupt identity must rotate the provider target before registering a new identity.
        null
    }

    @Synchronized
    override fun write(installation: PushInstallation) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(PushInstallation.serializer(), installation).encodeToByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
