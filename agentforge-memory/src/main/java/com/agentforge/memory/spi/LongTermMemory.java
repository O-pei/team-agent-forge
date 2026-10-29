package com.agentforge.memory.spi;

import java.util.List;

/**
 * 长期记忆：跨会话的事实/偏好存储与语义召回。
 */
public interface LongTermMemory {

    void saveFact(Long tenantId, Long userId, String fact, String category, double confidence);

    List<LongTermFact> recall(Long tenantId, Long userId, String query, int topK);

    boolean deleteFact(Long tenantId, Long userId, Long factId);
}
