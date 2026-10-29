package com.agentforge.security.controller;

import com.agentforge.common.api.ApiResponse;
import com.agentforge.security.dto.CurrentUserResponse;
import com.agentforge.security.dto.LoginRequest;
import com.agentforge.security.dto.LoginResponse;
import com.agentforge.security.dto.PasswordForgotRequest;
import com.agentforge.security.dto.PasswordResetRequest;
import com.agentforge.security.dto.RefreshTokenRequest;
import com.agentforge.security.dto.RegisterRequest;
import com.agentforge.security.dto.SendEmailCodeRequest;
import com.agentforge.security.dto.SendEmailCodeResponse;
import com.agentforge.security.dto.SmsCodeRequest;
import com.agentforge.security.service.AuthService;
import com.agentforge.security.util.RequestUtils;
import com.agentforge.security.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（契约 §2.1，Base path /api/v1/auth）。
 * <p>匿名端点：email-code / register / login / refresh / password/** / sms-code；
 * 需要登录：logout / me。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 发送邮箱验证码。 */
    @PostMapping("/email-code")
    public ApiResponse<SendEmailCodeResponse> sendEmailCode(@Valid @RequestBody SendEmailCodeRequest request,
                                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.sendEmailCode(request, RequestUtils.clientIp(httpRequest)));
    }

    /** 邮箱注册。 */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /** 登录（账号支持邮箱或用户名）。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request,
                RequestUtils.userAgent(httpRequest), RequestUtils.clientIp(httpRequest)));
    }

    /** 刷新 token（旧 refresh 一次性失效）。 */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.refresh(request.refreshToken(),
                RequestUtils.userAgent(httpRequest), RequestUtils.clientIp(httpRequest)));
    }

    /** 登出：拉黑 access token + 吊销 refresh token。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                    HttpServletRequest httpRequest) {
        authService.logout(SecurityUtils.currentUserIdOrThrow(),
                RequestUtils.bearerToken(httpRequest),
                request == null ? null : request.refreshToken());
        return ApiResponse.ok();
    }

    /** 当前用户：用户 + 角色 + 权限 + 菜单。 */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me() {
        return ApiResponse.ok(authService.currentUser(SecurityUtils.currentUserIdOrThrow()));
    }

    /** 忘记密码：发送重置验证码（不暴露邮箱是否存在）。 */
    @PostMapping("/password/forgot")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody PasswordForgotRequest request) {
        authService.forgotPassword(request.email());
        return ApiResponse.ok();
    }

    /** 重置密码。 */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok();
    }

    /** 短信验证码（v0.1.0 预留）：未配置通道返回 50300 sms-not-configured。 */
    @PostMapping("/sms-code")
    public ApiResponse<Void> smsCode(@Valid @RequestBody SmsCodeRequest request) {
        authService.sendSmsCode(request.phone());
        return ApiResponse.ok();
    }
}
