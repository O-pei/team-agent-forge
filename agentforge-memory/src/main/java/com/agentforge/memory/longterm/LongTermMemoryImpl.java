package com.agentforge.memory.longterm;

import com.agentforge.memory.fact.FactRecord;
import com.agentforge.memory.fact.FactRepository;
import com.agentforge.memory.spi.LongTermFact;
import com.agentforge.memory.spi.LongTermMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 长期记忆实现：保存事实与偏好，按词面相似度召回；Redis 不可用时落内存 Map。
 * 召回分数为 0.7 乘词面相似度加 0.3 乘置信度，query 为空时按置信度与时间召回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryImpl implements LongTermMemory {

    private static final double SIMILARITY_WEIGHT = 0.7;
    private static final double CONFIDENCE_WEIGHT = 0.3;
    private static final double MIN_SCORE = 0.01;

    private final FactRepository factRepository;

    @Override
    public void saveFact(Long tenantId, Long userId, String fact, String category, double confidence) {
        if (fact == null || fact.isBlank()) {
            log.debug("忽略空长期记忆: tenantId={}, userId={}", tenantId, userId);
            return;
        }
        FactRecord record = factRepository.save(tenantId, userId, fact.trim(),
                category == null || category.isBlank() ? "general" : category.trim(),
                Math.max(0.0, Math.min(1.0, confidence)));
        log.debug("保存长期记忆: tenantId={}, userId={}, id={}, category={}",
                tenantId, userId, record.id(), record.category());
    }

    @Override
    public List<LongTermFact> recall(Long tenantId, Long userId, String query, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        List<FactRecord> records = factRepository.findByScope(tenantId, userId);
        if (records.isEmpty()) {
            return List.of();
        }
        boolean queryBlank = query == null || query.isBlank();
        List<ScoredFact> scored = records.stream()
                .map(record -> {
                    double score = queryBlank
                            ? record.confidence()
                            : SIMILARITY_WEIGHT * MemorySimilarity.similarity(query, record.fact())
                            + CONFIDENCE_WEIGHT * record.confidence();
                    return new ScoredFact(record, score);
                })
                .filter(scoredFact -> scoredFact.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(ScoredFact::score).reversed()
                        .thenComparing(scoredFact -> scoredFact.record().createdAt(),
                                Comparator.reverseOrder()))
                .limit(topK)
                .toList();
        return scored.stream()
                .map(scoredFact -> new LongTermFact(
                        scoredFact.record().id(),
                        scoredFact.record().fact(),
                        scoredFact.record().category(),
                        Math.round(scoredFact.score() * 10000d) / 10000d,
                        scoredFact.record().createdAt()))
                .toList();
    }

    @Override
    public boolean deleteFact(Long tenantId, Long userId, Long factId) {
        return factRepository.delete(tenantId, userId, factId);
    }

    private record ScoredFact(FactRecord record, double score) {
    }
}
