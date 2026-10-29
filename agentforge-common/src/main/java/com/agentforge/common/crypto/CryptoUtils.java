package com.agentforge.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具（模型密钥、数据源密码等敏感字段入库前必须使用）。
 *
 * <p>主密钥来源（按优先级）：</p>
 * <ol>
 *   <li>系统属性 {@code agentforge.crypto.key}（由 Spring 配置 {@code agentforge.crypto.key} 注入，见
 *       agentforge-audit 的配置类）；</li>
 *   <li>环境变量 {@code AGENTFORGE_CRYPTO_KEY}；</li>
 *   <li>开发期内置默认值（<b>仅开发/测试可用，生产必须通过环境变量覆盖</b>）。</li>
 * </ol>
 *
 * <p>密文格式：{@code v1:} + Base64(12 字节随机 IV + GCM 密文/认证标签)。密钥由口令经 SHA-256 派生，
 * 因此口令可以是任意长度字符串；更换密钥后旧密文不可解密。</p>
 */
public final class CryptoUtils {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String TRANSFORMATION_KEY = "AES";
    private static final int TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final String CIPHER_PREFIX = "v1:";
    private static final String KEY_PROPERTY = "agentforge.crypto.key";
    private static final String KEY_ENV = "AGENTFORGE_CRYPTO_KEY";

    /** 仅本地开发/单元测试使用的默认口令，生产环境必须通过 AGENTFORGE_CRYPTO_KEY 覆盖。 */
    private static final String DEFAULT_DEV_KEY = "agentforge-dev-only-crypto-key-change-me";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKeySpec secretKey;

    private CryptoUtils() {
    }

    /**
     * 显式配置主口令（Spring 启动时注入 {@code agentforge.crypto.key}）。
     * 传入空值时忽略，继续按系统属性 / 环境变量 / 开发默认值解析。
     */
    public static void configure(String secret) {
        if (secret != null && !secret.isBlank()) {
            secretKey = deriveKey(secret);
        }
    }

    /**
     * 加密。
     *
     * @return {@code null}/{@code ""} 原样返回（表示未配置），否则返回 {@code v1:...} 密文
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new CryptoException("加密失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解密。
     *
     * @return {@code null}/{@code ""} 原样返回；兼容历史明文（非 {@code v1:} 前缀）直接返回原值
     */
    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (!ciphertext.startsWith(CIPHER_PREFIX)) {
            // 兼容尚未加密的历史数据
            return ciphertext;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(CIPHER_PREFIX.length()));
            if (payload.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("密文长度不合法");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] encrypted = new byte[payload.length - IV_LENGTH_BYTES];
            System.arraycopy(payload, IV_LENGTH_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new CryptoException("解密失败: " + ex.getMessage(), ex);
        }
    }

    /** 密钥是否已显式配置（用于健康检查/启动日志，不暴露密钥内容）。 */
    public static boolean explicitlyConfigured() {
        return secretKey != null;
    }

    private static SecretKeySpec key() {
        SecretKeySpec current = secretKey;
        if (current != null) {
            return current;
        }
        synchronized (CryptoUtils.class) {
            if (secretKey == null) {
                String configured = System.getProperty(KEY_PROPERTY);
                if (configured == null || configured.isBlank()) {
                    configured = System.getenv(KEY_ENV);
                }
                if (configured == null || configured.isBlank()) {
                    configured = DEFAULT_DEV_KEY;
                }
                secretKey = deriveKey(configured);
            }
            return secretKey;
        }
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, TRANSFORMATION_KEY);
        } catch (NoSuchAlgorithmException ex) {
            throw new CryptoException("初始化加密算法失败: " + ex.getMessage(), ex);
        }
    }

    /** 加解密异常（不回显密文/密钥）。 */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
