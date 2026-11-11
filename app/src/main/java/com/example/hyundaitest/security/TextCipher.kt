package com.example.hyundaitest.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * 글을 암호화·복호화하는 약속 (보안 A: 대나무숲 암호화).
 * 저장소는 이 인터페이스만 쓰므로, 테스트에서는 가짜 암호기로 바꿔 끼울 수 있다.
 */
interface TextCipher {
    /** 평문 → 저장용 문자열 */
    fun encrypt(plainText: String): String

    /** 저장용 문자열 → 평문. 키가 없거나 내용이 변조되면 예외 */
    fun decrypt(encryptedText: String): String
}

/** 키가 어디에 보관돼 있는지 (보안 상태 화면용) */
enum class KeyStorageLevel(val label: String) {
    STRONGBOX("전용 보안 칩(StrongBox)"),
    TRUSTED_ENVIRONMENT("보안 하드웨어 영역(TEE)"),
    SOFTWARE("소프트웨어 (에뮬레이터 등)"),
    UNKNOWN("확인 불가"),
}

/**
 * 안드로이드 키스토어를 쓰는 AES-256-GCM 암호기.
 *
 * - 키는 키스토어 안에서 만들어지고 밖으로 꺼낼 수 없다. 앱 파일을 통째로 복사해 가도 키가 없어 읽을 수 없다.
 * - GCM 은 암호화와 함께 변조 검사를 한다. 저장 파일을 누가 고치면 복호화가 실패한다.
 * - 가능하면 StrongBox(전용 보안 칩)에 키를 만들고, 없으면 일반 보안 영역(TEE)에 만든다.
 * - 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문·인증 태그)
 */
class KeystoreTextCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) : TextCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // IV 는 키스토어가 매번 새로 만든다
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return FORMAT_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(encryptedText: String): String {
        require(encryptedText.startsWith(FORMAT_PREFIX)) { "알 수 없는 암호문 형식" }
        val bytes = Base64.decode(encryptedText.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        require(bytes.size > IV_SIZE_BYTES) { "암호문이 너무 짧음" }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val body = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    /** 키가 보관된 곳. 키가 아직 없으면 만든 뒤 확인한다. */
    fun keyStorageLevel(): KeyStorageLevel = try {
        val key = getOrCreateKey()
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorageLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorageLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyStorageLevel.SOFTWARE
                else -> KeyStorageLevel.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyStorageLevel.TRUSTED_ENVIRONMENT else KeyStorageLevel.SOFTWARE
        }
    } catch (e: Exception) {
        KeyStorageLevel.UNKNOWN
    }

    // ------------------------------------------------------------------

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return tryCreateKey(useStrongBox = true) ?: createKey(useStrongBox = false)
    }

    /** StrongBox 가 없는 기기에서는 null */
    private fun tryCreateKey(useStrongBox: Boolean): SecretKey? = try {
        createKey(useStrongBox)
    } catch (e: Exception) {
        null
    }

    private fun createKey(useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(useStrongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "shimpyo_bamboo_key"
        const val FORMAT_PREFIX = "v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
    }
}
