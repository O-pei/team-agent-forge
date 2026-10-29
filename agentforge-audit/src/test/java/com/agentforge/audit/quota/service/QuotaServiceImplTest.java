package com.agentforge.audit.quota.service;

import com.agentforge.audit.quota.QuotaStatus;
import com.agentforge.audit.quota.api.QuotaTierCommand;
import com.agentforge.audit.quota.model.QuotaGrantLogEntity;
import com.agentforge.audit.quota.model.QuotaTierEntity;
import com.agentforge.audit.quota.model.UserQuotaEntity;
import com.agentforge.audit.quota.repository.QuotaGrantLogRepository;
import com.agentforge.audit.quota.repository.QuotaTierRepository;
import com.agentforge.audit.quota.repository.UserQuotaRepository;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import com.agentforge.audit.repository.TokenUsageRepository;
import com.agentforge.security.spi.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 额度服务单测：套餐解析、额度公式、超限异常、分配流水与重置语义。
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 29, 10, 0).atZone(ZONE).toInstant(), ZONE);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 10, 29, 0, 0);

    @Mock
    private QuotaTierRepository quotaTierRepository;
    @Mock
    private UserQuotaRepository userQuotaRepository;
    @Mock
    private QuotaGrantLogRepository quotaGrantLogRepository;
    @Mock
    private TokenUsageRepository tokenUsageRepository;
    @Mock
    private UserDirectory userDirectory;

    private QuotaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QuotaServiceImpl(quotaTierRepository, userQuotaRepository, quotaGrantLogRepository,
                tokenUsageRepository, userDirectory, "Asia/Shanghai", false, CLOCK);
    }

    @Test
    void status_freeTierComputesDailyAndTotalRemaining() {
        QuotaTierEntity free = tier(1L, "FREE", 20_000, 200_000);
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(quotaTierRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(tokenUsageRepository.sumTotalTokensByUserSince(7L, DAY_START)).thenReturn(120L);
        when(tokenUsageRepository.sumTotalTokensByUser(7L)).thenReturn(1_200L);

        QuotaStatus status = service.status(7L);

        assertEquals("FREE", status.tierCode());
        assertFalse(status.unlimited());
        assertEquals(20_000, status.dailyLimit());
        assertEquals(120, status.dailyUsed());
        assertEquals(19_880, status.dailyRemaining());
        assertEquals(200_000, status.totalLimit());
        assertEquals(1_200, status.totalUsed());
        assertEquals(198_800, status.totalRemaining());
        assertEquals(0, status.grantedTokens());
        assertEquals(LocalDateTime.of(2026, 10, 30, 0, 0), status.resetAt());
    }

    @Test
    void status_totalLimitAddsGrantedTokens() {
        QuotaTierEntity pro = tier(2L, "PRO", 200_000, 500_000);
        UserQuotaEntity quota = userQuota(7L, 2L, null, null, 1_000);
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.of(quota));
        when(quotaTierRepository.findById(2L)).thenReturn(Optional.of(pro));
        when(tokenUsageRepository.sumTotalTokensByUserSince(7L, DAY_START)).thenReturn(0L);
        when(tokenUsageRepository.sumTotalTokensByUser(7L)).thenReturn(0L);

        QuotaStatus status = service.status(7L);

        assertEquals(501_000, status.totalLimit());
        assertEquals(1_000, status.grantedTokens());
        assertEquals(501_000, status.totalRemaining());
    }

    @Test
    void status_superAdminBypassMarksUnlimited() {
        QuotaServiceImpl bypassService = new QuotaServiceImpl(quotaTierRepository, userQuotaRepository,
                quotaGrantLogRepository, tokenUsageRepository, userDirectory, "Asia/Shanghai", true, CLOCK);
        QuotaTierEntity free = tier(1L, "FREE", 20_000, 200_000);
        when(userQuotaRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(quotaTierRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(tokenUsageRepository.sumTotalTokensByUserSince(1L, DAY_START)).thenReturn(99_999L);
        when(tokenUsageRepository.sumTotalTokensByUser(1L)).thenReturn(999_999L);
        when(userDirectory.hasRole(1L, "SUPER_ADMIN")).thenReturn(true);

        QuotaStatus status = bypassService.status(1L);

        assertTrue(status.unlimited());
        assertEquals(0, status.dailyLimit());
        assertEquals(0, status.totalLimit());
        assertEquals(-1, status.dailyRemaining());
        assertEquals(-1, status.totalRemaining());
    }

    @Test
    void assertCanConsume_dailyExhaustedThrowsQuotaExceeded() {
        QuotaTierEntity free = tier(1L, "FREE", 20_000, 200_000);
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(quotaTierRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(tokenUsageRepository.sumTotalTokensByUserSince(7L, DAY_START)).thenReturn(20_000L);
        when(tokenUsageRepository.sumTotalTokensByUser(7L)).thenReturn(20_000L);

        QuotaExceededException exception = assertThrows(QuotaExceededException.class,
                () -> service.assertCanConsume(7L));

        assertTrue(exception.getMessage().contains("今日 Token 额度已用尽"));
        assertTrue(exception.getMessage().contains("2026-10-30 00:00"));
    }

    @Test
    void assertCanConsume_withinLimitDoesNotThrow() {
        QuotaTierEntity free = tier(1L, "FREE", 20_000, 200_000);
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(quotaTierRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(tokenUsageRepository.sumTotalTokensByUserSince(7L, DAY_START)).thenReturn(100L);
        when(tokenUsageRepository.sumTotalTokensByUser(7L)).thenReturn(100L);

        service.assertCanConsume(7L);
    }

    @Test
    void grantTokensAccumulatesAndWritesLedger() {
        QuotaTierEntity pro = tier(2L, "PRO", 200_000, 500_000);
        UserQuotaEntity quota = userQuota(7L, 2L, null, null, 100);
        when(userDirectory.findUser(7L)).thenReturn(user(7L, "alice"));
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.of(quota));
        when(quotaTierRepository.findById(2L)).thenReturn(Optional.of(pro));
        when(tokenUsageRepository.sumTotalTokensByUserSince(7L, DAY_START)).thenReturn(0L);
        when(tokenUsageRepository.sumTotalTokensByUser(7L)).thenReturn(0L);

        service.grantTokens(7L, 500L, "补偿", 100L);

        assertEquals(600L, quota.getGrantedTokens());
        ArgumentCaptor<QuotaGrantLogEntity> captor = ArgumentCaptor.forClass(QuotaGrantLogEntity.class);
        verify(quotaGrantLogRepository).save(captor.capture());
        QuotaGrantLogEntity saved = captor.getValue();
        assertEquals(500L, saved.getAmount());
        assertEquals(100L, saved.getOperatorId());
        assertEquals(500_600L, saved.getBalanceAfter());
        assertEquals("补偿", saved.getReason());
    }

    @Test
    void resetUserQuotaClearsTierAndOverridesButKeepsGranted() {
        UserQuotaEntity quota = userQuota(7L, 2L, 5L, 10L, 300);
        when(userDirectory.findUser(7L)).thenReturn(user(7L, "alice"));
        when(userQuotaRepository.findByUserId(7L)).thenReturn(Optional.of(quota));

        service.resetUserQuota(7L);

        assertNull(quota.getTierId());
        assertNull(quota.getDailyLimitOverride());
        assertNull(quota.getTotalLimitOverride());
        assertEquals(300L, quota.getGrantedTokens());
    }

    @Test
    void createTier_duplicateCodeThrowsConflict() {
        when(quotaTierRepository.existsByCode("PRO")).thenReturn(true);
        QuotaTierCommand command = new QuotaTierCommand("pro", "专业版", null, 1L, 2L, 1, true);

        assertThrows(BizException.class, () -> service.createTier(command));
    }

    @Test
    void deleteTier_builtinThrowsConflict() {
        QuotaTierEntity free = tier(1L, "FREE", 20_000, 200_000);
        free.setBuiltin(true);
        when(quotaTierRepository.findById(1L)).thenReturn(Optional.of(free));

        assertThrows(BizException.class, () -> service.deleteTier(1L));
    }

    private static QuotaTierEntity tier(long id, String code, long daily, long total) {
        QuotaTierEntity entity = new QuotaTierEntity();
        entity.setId(id);
        entity.setCode(code);
        entity.setName(code + " 套餐");
        entity.setDailyTokenLimit(daily);
        entity.setTotalTokenLimit(total);
        entity.setSortOrder((int) id);
        entity.setEnabled(true);
        entity.setBuiltin(true);
        return entity;
    }

    private static UserQuotaEntity userQuota(long userId, Long tierId, Long daily, Long total, long granted) {
        UserQuotaEntity entity = new UserQuotaEntity();
        entity.setUserId(userId);
        entity.setTenantId(1L);
        entity.setTierId(tierId);
        entity.setDailyLimitOverride(daily);
        entity.setTotalLimitOverride(total);
        entity.setGrantedTokens(granted);
        return entity;
    }

    private static UserDirectory.UserBrief user(long id, String username) {
        return new UserDirectory.UserBrief(id, username, username + "@example.com", null, "ACTIVE");
    }
}
