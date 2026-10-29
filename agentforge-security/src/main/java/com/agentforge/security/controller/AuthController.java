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
 * 认证接口，Base path /api/v1/auth；匿名端点由 SecurityConfig 白名单放行，logout 与 me 需登录。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/email-code")
    public ApiResponse<SendEmailCodeResponse> sendEmailCode(@Valid @RequestBody SendEmailCodeRequest request,
                                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.sendEmailCode(request, RequestUtils.clientIp(httpRequest)));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request,
                RequestUtils.userAgent(httpRequest), RequestUtils.clientIp(httpRequest)));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request,
                                              HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.refresh(request.refreshToken(),
                RequestUtils.userAgent(httpRequest), RequestUtils.clientIp(httpRequest)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                    HttpServletRequest httpRequest) {
        authService.logout(SecurityUtils.currentUserIdOrThrow(),
                RequestUtils.bearerToken(httpRequest),
                request == null ? null : request.refreshToken());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me() {
        return ApiResponse.ok(authService.currentUser(SecurityUtils.currentUserIdOrThrow()));
    }

    /** 忘记密码：邮箱不存在时同样返回成功，避免账号枚举。 */
    @PostMapping("/password/forgot")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody PasswordForgotRequest request) {
        authService.forgotPassword(request.email());
        return ApiResponse.ok();
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok();
    }

    /** 短信验证码预留；未配置短信通道时返回 50300 sms-not-configured。 */
    @PostMapping("/sms-code")
    public ApiResponse<Void> smsCode(@Valid @RequestBody SmsCodeRequest request) {
        authService.sendSmsCode(request.phone());
        return ApiResponse.ok();
    }
}
