package ai.pleos.destinations.vehicle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bearer/invitation secrets never enter preferences, backup, URLs, saved view state, or logs. */
class SecureSessionStore(context: Context) : SessionStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "room-session.bin"))
    private val alias = "pleos-destination-share.room.v1"
    private val aad = "pleos-destination-share.session.v1".toByteArray(Charsets.UTF_8)

    @Synchronized override fun save(credentials: RoomCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val plain = RoomProtocol.storage(credentials).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(1); it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted) }
        }.toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized override fun load(): LoadedSession {
        if (!file.baseFile.exists()) return LoadedSession(null, false)
        return try {
            require(file.baseFile.length() <= 16_384)
            val input = DataInputStream(ByteArrayInputStream(file.readFully()))
            require(input.readInt() == 1)
            val size = input.readInt(); require(size == 12)
            val iv = ByteArray(size); input.readFully(iv)
            val encrypted = input.readBytes(); require(encrypted.size >= 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(encrypted)
            val body = try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
            LoadedSession(RoomProtocol.credentials(body.getString("baseUrl"), body), false)
        } catch (_: Exception) { clear(); LoadedSession(null, true) }
    }

    @Synchronized override fun clear() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
