package com.example.worktimetracker.location.evidence

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 环境原始标识（Wi-Fi SSID/BSSID、蓝牙名称/地址、基站标识等）只允许以加盐哈希形式
 * 离开内存；数据库与普通日志均不得出现原始值或盐。
 */
object EnvironmentIdentifierHasher {

    /** 使用本地盐与 NUL 分隔字段计算 SHA-256，返回 64 位十六进制字符串。 */
    fun hash(salt: ByteArray, fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(FIELD_SEPARATOR)
        fields.forEach { field ->
            digest.update(field.toByteArray(Charsets.UTF_8))
            digest.update(FIELD_SEPARATOR)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val FIELD_SEPARATOR = byteArrayOf(0)
}

/**
 * 应用私有的指纹哈希盐，保存在 SharedPreferences 中；不存在时用 SecureRandom 生成
 * 32 字节并以 Base64 持久化。盐本身不得写入普通日志。
 */
class EnvironmentSaltStore(private val context: Context) {

    fun getOrCreate(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SALT, null)
        if (stored != null) {
            val decoded = Base64.decode(stored, Base64.NO_WRAP)
            if (decoded.size == SALT_LENGTH) return decoded
        }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
        return salt
    }

    companion object {
        private const val PREFS_NAME = "environment_fingerprint_secret"
        private const val KEY_SALT = "identifier_hash_salt"
        private const val SALT_LENGTH = 32
    }
}
