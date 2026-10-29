package com.agentforge.audit.model;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 模型单价实体（ai_model_price 表）：按 1K token 计价，用于实时估算成本。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_model_price")
public class ModelPriceEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "input_price_per_1k", nullable = false, precision = 18, scale = 6)
    private BigDecimal inputPricePer1k = BigDecimal.ZERO;

    @Column(name = "output_price_per_1k", nullable = false, precision = 18, scale = 6)
    private BigDecimal outputPricePer1k = BigDecimal.ZERO;

    @Column(nullable = false, length = 8)
    private String currency = "CNY";
}
