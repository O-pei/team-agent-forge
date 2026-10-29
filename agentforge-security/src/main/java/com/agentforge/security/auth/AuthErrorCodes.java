package com.agentforge.security.auth;

/**
 * 认证相关业务错误码，登记于 docs/04-API契约.md 附录 B。
 */
public final class AuthErrorCodes {

    /** access token 过期。 */
    public static final int ACCESS_TOKEN_EXPIRED = 40101;

    /** refresh token 无效/已吊销。 */
    public static final int REFRESH_TOKEN_INVALID = 40102;

    /** 账号被锁定。 */
    public static final int ACCOUNT_LOCKED = 40103;

    /** 邮箱未验证。 */
    public static final int EMAIL_NOT_VERIFIED = 40104;

    /** 验证码错误或已过期。 */
    public static final int EMAIL_CODE_INVALID = 40201;

    /** 验证码发送过于频繁。 */
    public static final int EMAIL_CODE_TOO_FREQUENT = 40202;

    private AuthErrorCodes() {
    }
}
