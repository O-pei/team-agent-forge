package com.agentforge.audit.model;

import com.agentforge.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 模型配置实体（ai_model 表）。API Key 以 AES-GCM 密文入库，接口不回显。
 */
@Getter
@Setter
@Entity
@Table(name = "ai_model")
public class ModelConfigEntity extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name;

    /** DASHSCOPE / OPENAI_COMPATIBLE / MOCK */
    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "base_url", length = 512)
    private String baseUrl;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "api_key_encrypted", length = 1024)
    private String apiKeyEncrypted;

    @Column
    private Double temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "is_default", nullable = false)
    private Boolean defaultModel = Boolean.FALSE;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;
}
