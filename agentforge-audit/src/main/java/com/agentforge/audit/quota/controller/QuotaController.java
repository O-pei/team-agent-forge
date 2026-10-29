package com.agentforge.audit.quota.controller;

import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.quota.QuotaStatus;
import com.agentforge.audit.quota.api.QuotaGrantCommand;
import com.agentforge.audit.quota.api.QuotaGrantView;
import com.agentforge.audit.quota.api.QuotaTierCommand;
import com.agentforge.audit.quota.api.QuotaTierView;
import com.agentforge.audit.quota.api.UserQuotaCommand;
import com.agentforge.audit.quota.api.UserQuotaView;
import com.agentforge.common.api.ApiResponse;
import com.agentforge.common.api.PageResult;
import com.agentforge.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token 额度接口：当前用户额度、套餐 CRUD、用户额度覆盖、额度重置与手动分配。
 * 所有接口路径与契约 docs/04-API契约.md 第 2.7 节保持一致。
 */
@RestController
@RequestMapping("/api/v1/quota")
@RequiredArgsConstructor
@Validated
public class QuotaController {

    private final QuotaService quotaService;

    /**
     * 当前登录用户的额度快照，登录即可访问。
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<QuotaStatus> myQuota() {
        return ApiResponse.ok(quotaService.status(SecurityUtils.currentUserIdOrThrow()));
    }

    /**
     * 套餐列表，包含内置标记与各套餐使用人数。
     */
    @GetMapping("/tiers")
    @PreAuthorize("hasAuthority('quota:read')")
    public ApiResponse<List<QuotaTierView>> listTiers() {
        return ApiResponse.ok(quotaService.listTiers());
    }

    /**
     * 新建套餐，编码统一转大写且必须唯一。
     */
    @PostMapping("/tiers")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<QuotaTierView> createTier(@Valid @RequestBody QuotaTierCommand cmd) {
        return ApiResponse.ok(quotaService.createTier(cmd));
    }

    /**
     * 更新套餐，编码不可修改；额度、排序与启停均可按需传入。
     */
    @PutMapping("/tiers/{id}")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<QuotaTierView> updateTier(@PathVariable("id") Long id,
                                                 @Valid @RequestBody QuotaTierCommand cmd) {
        return ApiResponse.ok(quotaService.updateTier(id, cmd));
    }

    /**
     * 删除套餐；内置套餐或仍有用户引用时返回 409。
     */
    @DeleteMapping("/tiers/{id}")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<Void> deleteTier(@PathVariable("id") Long id) {
        quotaService.deleteTier(id);
        return ApiResponse.ok(null);
    }

    /**
     * 用户额度分页，按用户目录返回套餐、当日用量与总额度信息。
     */
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('quota:read')")
    public ApiResponse<PageResult<UserQuotaView>> listUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(quotaService.listUsers(keyword, page, size));
    }

    /**
     * 设置用户套餐与每日/总额度覆盖值；字段缺省或 null 表示继承套餐，0 表示不限。
     */
    @PutMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<UserQuotaView> updateUserQuota(@PathVariable("userId") Long userId,
                                                      @Valid @RequestBody UserQuotaCommand cmd) {
        return ApiResponse.ok(quotaService.updateUserQuota(userId, cmd));
    }

    /**
     * 清除用户额度覆盖，恢复继承套餐；手动分配额度保留。
     */
    @DeleteMapping("/users/{userId}/override")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<Void> resetUserQuota(@PathVariable("userId") Long userId) {
        quotaService.resetUserQuota(userId);
        return ApiResponse.ok(null);
    }

    /**
     * 手动给用户追加 Token 额度并写入分配流水。
     */
    @PostMapping("/users/{userId}/grant")
    @PreAuthorize("hasAuthority('quota:write')")
    public ApiResponse<Void> grantTokens(@PathVariable("userId") Long userId,
                                         @Valid @RequestBody QuotaGrantCommand cmd) {
        quotaService.grantTokens(userId, cmd.amount(), cmd.reason(), SecurityUtils.currentUserId());
        return ApiResponse.ok(null);
    }

    /**
     * 用户额度分配流水，按创建时间倒序分页。
     */
    @GetMapping("/users/{userId}/grants")
    @PreAuthorize("hasAuthority('quota:read')")
    public ApiResponse<PageResult<QuotaGrantView>> listGrants(
            @PathVariable("userId") Long userId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.ok(quotaService.listGrants(userId, page, size));
    }
}
