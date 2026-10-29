package com.agentforge.audit.quota.service;

import com.agentforge.audit.model.TokenUsageEntity;
import com.agentforge.audit.quota.QuotaService;
import com.agentforge.audit.quota.QuotaStatus;
import com.agentforge.audit.quota.api.QuotaGrantView;
import com.agentforge.audit.quota.api.QuotaTierCommand;
import com.agentforge.audit.quota.api.QuotaTierView;
import com.agentforge.audit.quota.api.UserQuotaCommand;
import com.agentforge.audit.quota.api.UserQuotaView;
import com.agentforge.audit.quota.model.QuotaGrantLogEntity;
import com.agentforge.audit.quota.model.QuotaTierEntity;
import com.agentforge.audit.quota.model.UserQuotaEntity;
import com.agentforge.audit.quota.repository.QuotaGrantLogRepository;
import com.agentforge.audit.quota.repository.QuotaTierRepository;
import com.agentforge.audit.quota.repository.UserQuotaRepository;
import com.agentforge.audit.repository.TokenUsageRepository;
import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.api.PageResult;
import com.agentforge.common.context.TenantContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import com.agentforge.security.spi.UserDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 额度服务实现：解析用户套餐与覆盖额度，按 ai_token_usage.total_tokens 聚合当日与全量用量。
 * 额度 0 表示不限，总额度等于覆盖或套餐额度加手动分配；SUPER_ADMIN 可通过配置整体绕过限制。
 */
@Slf4j
@Service
public class QuotaServiceImpl implements QuotaService {

    private static final String DEFAULT_TIER_CODE = "FREE";
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter RESET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QuotaTierRepository quotaTierRepository;
    private final UserQuotaRepository userQuotaRepository;
    private final QuotaGrantLogRepository quotaGrantLogRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final UserDirectory userDirectory;
    private final String zone;
    private final boolean bypassSuperAdmin;
    private final Clock clock;

    @Autowired
    public QuotaServiceImpl(QuotaTierRepository quotaTierRepository,
                            UserQuotaRepository userQuotaRepository,
                            QuotaGrantLogRepository quotaGrantLogRepository,
                            TokenUsageRepository tokenUsageRepository,
                            UserDirectory userDirectory,
                            @Value("${agentforge.quota.zone:Asia/Shanghai}") String zone,
                            @Value("${agentforge.quota.bypass-super-admin:true}") boolean bypassSuperAdmin) {
        this(quotaTierRepository, userQuotaRepository, quotaGrantLogRepository, tokenUsageRepository,
                userDirectory, zone, bypassSuperAdmin, Clock.system(ZoneId.of(zone)));
    }

    QuotaServiceImpl(QuotaTierRepository quotaTierRepository,
                     UserQuotaRepository userQuotaRepository,
                     QuotaGrantLogRepository quotaGrantLogRepository,
                     TokenUsageRepository tokenUsageRepository,
                     UserDirectory userDirectory,
                     String zone,
                     boolean bypassSuperAdmin,
                     Clock clock) {
        this.quotaTierRepository = quotaTierRepository;
        this.userQuotaRepository = userQuotaRepository;
        this.quotaGrantLogRepository = quotaGrantLogRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userDirectory = userDirectory;
        this.zone = zone;
        this.bypassSuperAdmin = bypassSuperAdmin;
        this.clock = clock;
    }

    @Override
    public QuotaStatus status(long userId) {
        UserQuotaEntity userQuota = userQuotaRepository.findByUserId(userId).orElse(null);
        QuotaTierEntity tier = resolveTier(userQuota);
        LocalDateTime dayStart = currentDayStart();
        LocalDateTime resetAt = dayStart.plusDays(1);
        long dailyUsed = tokenUsageRepository.sumTotalTokensByUserSince(userId, dayStart);
        long totalUsed = tokenUsageRepository.sumTotalTokensByUser(userId);
        boolean superAdmin = bypassSuperAdmin && userDirectory.hasRole(userId, SUPER_ADMIN_ROLE);
        return buildStatus(userId, tier, userQuota, dailyUsed, totalUsed, resetAt, superAdmin);
    }

    @Override
    public void assertCanConsume(long userId) {
        QuotaStatus status = status(userId);
        if (status.unlimited()) {
            return;
        }
        if (status.dailyLimit() > 0 && status.dailyUsed() >= status.dailyLimit()) {
            throw new QuotaExceededException("今日 Token 额度已用尽：已用 " + status.dailyUsed()
                    + " / 每日上限 " + status.dailyLimit()
                    + "，将于 " + status.resetAt().format(RESET_TIME_FORMAT) + " 重置，或联系管理员提升额度");
        }
        if (status.totalLimit() > 0 && status.totalUsed() >= status.totalLimit()) {
            throw new QuotaExceededException("Token 总额度已用尽：已用 " + status.totalUsed()
                    + " / 总额度 " + status.totalLimit() + "，请联系管理员分配额度");
        }
    }

    @Override
    public List<QuotaTierView> listTiers() {
        Map<Long, Long> userCounts = toCountMap(quotaTierRepository.countUsersByTier());
        return quotaTierRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(tier -> toTierView(tier, userCounts.getOrDefault(tier.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public QuotaTierView createTier(QuotaTierCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.code()) || !StringUtils.hasText(cmd.name())) {
            throw BizException.badRequest("套餐编码和名称不能为空");
        }
        String code = cmd.code().trim().toUpperCase(Locale.ROOT);
        if (quotaTierRepository.existsByCode(code)) {
            throw BizException.conflict("套餐编码已存在: " + code);
        }
        QuotaTierEntity entity = new QuotaTierEntity();
        entity.setCode(code);
        entity.setName(cmd.name().trim());
        entity.setDescription(cmd.description());
        entity.setDailyTokenLimit(cmd.dailyTokenLimit() == null ? 0 : cmd.dailyTokenLimit());
        entity.setTotalTokenLimit(cmd.totalTokenLimit() == null ? 0 : cmd.totalTokenLimit());
        entity.setSortOrder(cmd.sortOrder() == null ? 0 : cmd.sortOrder());
        entity.setEnabled(cmd.enabled() == null || cmd.enabled());
        entity.setBuiltin(false);
        quotaTierRepository.save(entity);
        log.info("创建额度套餐: id={}, code={}", entity.getId(), code);
        return toTierView(entity, 0L);
    }

    @Override
    @Transactional
    public QuotaTierView updateTier(Long id, QuotaTierCommand cmd) {
        QuotaTierEntity entity = findTier(id);
        if (cmd == null) {
            throw BizException.badRequest("套餐参数不能为空");
        }
        if (StringUtils.hasText(cmd.name())) {
            entity.setName(cmd.name().trim());
        }
        if (cmd.description() != null) {
            entity.setDescription(cmd.description());
        }
        if (cmd.dailyTokenLimit() != null) {
            entity.setDailyTokenLimit(cmd.dailyTokenLimit());
        }
        if (cmd.totalTokenLimit() != null) {
            entity.setTotalTokenLimit(cmd.totalTokenLimit());
        }
        if (cmd.sortOrder() != null) {
            entity.setSortOrder(cmd.sortOrder());
        }
        if (cmd.enabled() != null) {
            entity.setEnabled(cmd.enabled());
        }
        quotaTierRepository.save(entity);
        log.info("更新额度套餐: id={}, code={}", id, entity.getCode());
        return toTierView(entity, userQuotaRepository.countByTierId(id));
    }

    @Override
    @Transactional
    public void deleteTier(Long id) {
        QuotaTierEntity entity = findTier(id);
        if (entity.isBuiltin()) {
            throw BizException.conflict("内置套餐不可删除: " + entity.getCode());
        }
        if (userQuotaRepository.existsByTierId(id)) {
            throw BizException.conflict("仍有用户使用该套餐，无法删除: " + entity.getCode());
        }
        quotaTierRepository.delete(entity);
        log.info("删除额度套餐: id={}, code={}", id, entity.getCode());
    }

    @Override
    public PageResult<UserQuotaView> listUsers(String keyword, int page, int size) {
        int safePage = page <= 0 ? 1 : page;
        int safeSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        UserDirectory.Page directoryPage = userDirectory.pageUsers(keyword, safePage, safeSize);
        List<UserDirectory.UserBrief> users = directoryPage.records();
        if (users == null || users.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }
        List<Long> userIds = users.stream().map(UserDirectory.UserBrief::id).toList();
        LocalDateTime dayStart = currentDayStart();
        LocalDateTime resetAt = dayStart.plusDays(1);
        Map<Long, UserQuotaEntity> quotas = userQuotaRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(UserQuotaEntity::getUserId, Function.identity(), (left, right) -> left));
        Map<Long, QuotaTierEntity> tiers = loadTiers(quotas.values());
        Map<Long, Long> dailyUsage = toCountMap(tokenUsageRepository.sumTotalTokensByUserIdsSince(userIds, dayStart));
        Map<Long, Long> totalUsage = toCountMap(tokenUsageRepository.sumTotalTokensByUserIds(userIds));
        QuotaTierEntity freeTier = resolveTier(null);
        List<UserQuotaView> views = new ArrayList<>(users.size());
        for (UserDirectory.UserBrief user : users) {
            UserQuotaEntity quota = quotas.get(user.id());
            QuotaTierEntity tier = tierOf(quota, tiers, freeTier);
            views.add(toUserQuotaView(user, tier, quota,
                    dailyUsage.getOrDefault(user.id(), 0L), totalUsage.getOrDefault(user.id(), 0L), resetAt));
        }
        return PageResult.of(views, directoryPage.total(), safePage, safeSize);
    }

    @Override
    @Transactional
    public UserQuotaView updateUserQuota(Long userId, UserQuotaCommand cmd) {
        if (cmd == null) {
            throw BizException.badRequest("额度参数不能为空");
        }
        UserDirectory.UserBrief user = requireUser(userId);
        QuotaTierEntity selectedTier = null;
        if (cmd.tierId() != null) {
            selectedTier = quotaTierRepository.findById(cmd.tierId())
                    .orElseThrow(() -> BizException.notFound("套餐不存在: " + cmd.tierId()));
            if (!selectedTier.isEnabled()) {
                throw BizException.badRequest("套餐已禁用: " + selectedTier.getCode());
            }
        }
        UserQuotaEntity quota = userQuotaRepository.findByUserId(userId).orElseGet(() -> {
            UserQuotaEntity created = new UserQuotaEntity();
            created.setUserId(userId);
            created.setTenantId(TenantContext.getCurrentTenantId());
            return created;
        });
        quota.setTierId(selectedTier == null ? null : selectedTier.getId());
        quota.setDailyLimitOverride(cmd.dailyLimit());
        quota.setTotalLimitOverride(cmd.totalLimit());
        userQuotaRepository.save(quota);
        log.info("更新用户额度: userId={}, tierId={}, dailyLimit={}, totalLimit={}",
                userId, quota.getTierId(), quota.getDailyLimitOverride(), quota.getTotalLimitOverride());
        return statusView(userId, user, quota);
    }

    @Override
    @Transactional
    public void resetUserQuota(Long userId) {
        requireUser(userId);
        UserQuotaEntity quota = userQuotaRepository.findByUserId(userId)
                .orElseThrow(() -> BizException.notFound("该用户没有额度配置: " + userId));
        quota.setTierId(null);
        quota.setDailyLimitOverride(null);
        quota.setTotalLimitOverride(null);
        userQuotaRepository.save(quota);
        log.info("清除用户额度覆盖: userId={}, grantedTokens={}", userId, quota.getGrantedTokens());
    }

    @Override
    @Transactional
    public UserQuotaView grantTokens(Long userId, long amount, String reason, Long operatorUserId) {
        if (amount <= 0) {
            throw BizException.badRequest("分配数量必须大于 0");
        }
        if (reason != null && reason.length() > 200) {
            throw BizException.badRequest("分配原因最长 200 字符");
        }
        UserDirectory.UserBrief user = requireUser(userId);
        UserQuotaEntity quota = userQuotaRepository.findByUserId(userId).orElseGet(() -> {
            UserQuotaEntity created = new UserQuotaEntity();
            created.setUserId(userId);
            created.setTenantId(TenantContext.getCurrentTenantId());
            return created;
        });
        long granted = quota.getGrantedTokens() + amount;
        quota.setGrantedTokens(granted);
        userQuotaRepository.save(quota);

        QuotaTierEntity tier = resolveTier(quota);
        long balanceAfter = totalLimitOf(tier, quota);
        QuotaGrantLogEntity logEntity = new QuotaGrantLogEntity();
        logEntity.setTenantId(quota.getTenantId());
        logEntity.setUserId(userId);
        logEntity.setOperatorId(operatorUserId);
        logEntity.setAmount(amount);
        logEntity.setReason(reason);
        logEntity.setBalanceAfter(balanceAfter);
        quotaGrantLogRepository.save(logEntity);
        log.info("手动分配 Token: userId={}, amount={}, balanceAfter={}, operatorId={}",
                userId, amount, balanceAfter, operatorUserId);
        return statusView(userId, user, quota);
    }

    @Override
    public PageResult<QuotaGrantView> listGrants(Long userId, int page, int size) {
        if (userId == null) {
            throw BizException.badRequest("用户 ID 不能为空");
        }
        int safePage = page <= 0 ? 1 : page;
        int safeSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<QuotaGrantLogEntity> result = quotaGrantLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        Set<Long> operatorIds = new HashSet<>();
        for (QuotaGrantLogEntity entity : result.getContent()) {
            if (entity.getOperatorId() != null) {
                operatorIds.add(entity.getOperatorId());
            }
        }
        Map<Long, String> operatorNames = new HashMap<>();
        for (Long operatorId : operatorIds) {
            UserDirectory.UserBrief operator = userDirectory.findUser(operatorId);
            if (operator != null) {
                operatorNames.put(operatorId, displayName(operator));
            }
        }
        List<QuotaGrantView> views = result.getContent().stream()
                .map(entity -> new QuotaGrantView(entity.getId(), entity.getOperatorId(),
                        operatorNames.get(entity.getOperatorId()), entity.getAmount(), entity.getReason(),
                        entity.getBalanceAfter(), entity.getCreatedAt()))
                .toList();
        return PageResult.of(views, result.getTotalElements(), safePage, safeSize);
    }

    private QuotaStatus buildStatus(long userId, QuotaTierEntity tier, UserQuotaEntity quota,
                                    long dailyUsed, long totalUsed, LocalDateTime resetAt, boolean forcedUnlimited) {
        long granted = quota == null ? 0 : quota.getGrantedTokens();
        long dailyLimit = quota != null && quota.getDailyLimitOverride() != null
                ? quota.getDailyLimitOverride() : tier.getDailyTokenLimit();
        long baseTotal = quota != null && quota.getTotalLimitOverride() != null
                ? quota.getTotalLimitOverride() : tier.getTotalTokenLimit();
        long totalLimit = baseTotal == 0 ? 0 : baseTotal + granted;
        if (forcedUnlimited) {
            dailyLimit = 0;
            totalLimit = 0;
        }
        boolean unlimited = dailyLimit == 0 && totalLimit == 0;
        long dailyRemaining = dailyLimit == 0 ? -1 : Math.max(0, dailyLimit - dailyUsed);
        long totalRemaining = totalLimit == 0 ? -1 : Math.max(0, totalLimit - totalUsed);
        return new QuotaStatus(userId, tier.getCode(), tier.getName(), dailyLimit, dailyUsed, dailyRemaining,
                totalLimit, totalUsed, totalRemaining, granted, unlimited, resetAt);
    }

    private QuotaTierEntity resolveTier(UserQuotaEntity quota) {
        if (quota != null && quota.getTierId() != null) {
            Optional<QuotaTierEntity> tier = quotaTierRepository.findById(quota.getTierId());
            if (tier.isPresent() && tier.get().isEnabled()) {
                return tier.get();
            }
        }
        return quotaTierRepository.findByCode(DEFAULT_TIER_CODE)
                .orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR, "内置套餐 FREE 不存在，请联系管理员"));
    }

    private QuotaTierEntity tierOf(UserQuotaEntity quota, Map<Long, QuotaTierEntity> tiers, QuotaTierEntity freeTier) {
        if (quota != null && quota.getTierId() != null) {
            QuotaTierEntity tier = tiers.get(quota.getTierId());
            if (tier != null && tier.isEnabled()) {
                return tier;
            }
        }
        return freeTier;
    }

    private Map<Long, QuotaTierEntity> loadTiers(Collection<UserQuotaEntity> quotas) {
        Set<Long> tierIds = quotas.stream().map(UserQuotaEntity::getTierId)
                .filter(id -> id != null).collect(Collectors.toSet());
        if (tierIds.isEmpty()) {
            return Map.of();
        }
        return quotaTierRepository.findAllById(tierIds).stream()
                .collect(Collectors.toMap(QuotaTierEntity::getId, Function.identity(), (left, right) -> left));
    }

    private UserQuotaView statusView(Long userId, UserDirectory.UserBrief user, UserQuotaEntity quota) {
        QuotaTierEntity tier = resolveTier(quota);
        LocalDateTime dayStart = currentDayStart();
        long dailyUsed = tokenUsageRepository.sumTotalTokensByUserSince(userId, dayStart);
        long totalUsed = tokenUsageRepository.sumTotalTokensByUser(userId);
        return toUserQuotaView(user, tier, quota, dailyUsed, totalUsed, dayStart.plusDays(1));
    }

    private UserQuotaView toUserQuotaView(UserDirectory.UserBrief user, QuotaTierEntity tier, UserQuotaEntity quota,
                                          long dailyUsed, long totalUsed, LocalDateTime resetAt) {
        long granted = quota == null ? 0 : quota.getGrantedTokens();
        long dailyLimit = quota != null && quota.getDailyLimitOverride() != null
                ? quota.getDailyLimitOverride() : tier.getDailyTokenLimit();
        long baseTotal = quota != null && quota.getTotalLimitOverride() != null
                ? quota.getTotalLimitOverride() : tier.getTotalTokenLimit();
        long totalLimit = baseTotal == 0 ? 0 : baseTotal + granted;
        boolean unlimited = dailyLimit == 0 && totalLimit == 0;
        long dailyRemaining = dailyLimit == 0 ? -1 : Math.max(0, dailyLimit - dailyUsed);
        long totalRemaining = totalLimit == 0 ? -1 : Math.max(0, totalLimit - totalUsed);
        return new UserQuotaView(user.id(), user.username(), user.email(), user.nickname(), user.status(),
                tier.getId(), tier.getCode(), tier.getName(), dailyLimit, dailyUsed, dailyRemaining,
                totalLimit, totalUsed, totalRemaining, granted, unlimited, resetAt);
    }

    private long totalLimitOf(QuotaTierEntity tier, UserQuotaEntity quota) {
        long baseTotal = quota != null && quota.getTotalLimitOverride() != null
                ? quota.getTotalLimitOverride() : tier.getTotalTokenLimit();
        long granted = quota == null ? 0 : quota.getGrantedTokens();
        return baseTotal == 0 ? 0 : baseTotal + granted;
    }

    private UserDirectory.UserBrief requireUser(Long userId) {
        if (userId == null) {
            throw BizException.badRequest("用户 ID 不能为空");
        }
        UserDirectory.UserBrief user = userDirectory.findUser(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在: " + userId);
        }
        return user;
    }

    private QuotaTierEntity findTier(Long id) {
        if (id == null) {
            throw BizException.badRequest("套餐 ID 不能为空");
        }
        return quotaTierRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("套餐不存在: " + id));
    }

    private static String displayName(UserDirectory.UserBrief user) {
        if (StringUtils.hasText(user.nickname())) {
            return user.nickname();
        }
        return StringUtils.hasText(user.username()) ? user.username() : "用户#" + user.id();
    }

    private LocalDateTime currentDayStart() {
        return LocalDate.now(clock).atStartOfDay();
    }

    private static QuotaTierView toTierView(QuotaTierEntity tier, long userCount) {
        return new QuotaTierView(tier.getId(), tier.getCode(), tier.getName(), tier.getDescription(),
                tier.getDailyTokenLimit(), tier.getTotalTokenLimit(), tier.getSortOrder(),
                tier.isEnabled(), tier.isBuiltin(), userCount, tier.getCreatedAt(), tier.getUpdatedAt());
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        if (rows == null) {
            return map;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            map.put(((Number) row[0]).longValue(),
                    row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return map;
    }
}
