package kr.together.road

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encryption plus no-backup private storage. Never put bearer secrets in Intents. */
class SecretStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "room.enc"))
    private val alias = "together-road-room-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(session.json().toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(cipher.iv.size); output.write(cipher.iv); output.write(encrypted); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun load(): Session? = try {
        val bytes = file.readFully(); require(bytes.size in 30..8192)
        val size = bytes[0].toInt() and 255; require(size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + size))) }
        Session.parse(JSONObject(cipher.doFinal(bytes.copyOfRange(1 + size, bytes.size)).toString(Charsets.UTF_8)))
    } catch (_: Exception) { file.delete(); null }
    fun clear() = file.delete()
}
