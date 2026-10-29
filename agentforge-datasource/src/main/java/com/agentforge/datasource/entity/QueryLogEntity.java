package com.agentforge.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 鏁版嵁婧愭煡璇㈠璁℃棩蹇楋紙ds_query_log锛夈€傚彧杩藉姞銆佷笉鏇存柊锛屽洜姝や笉缁ф壙 BaseEntity锛堟棤 version/updated_at锛夈€? */
@Getter
@Setter
@Entity
@Table(name = "ds_query_log")
public class QueryLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long datasourceId;

    private Long userId;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Column(nullable = false)
    private Boolean success;

    @Column(nullable = false)
    private Integer rowCount = 0;

    @Column(nullable = false)
    private Long elapsedMs = 0L;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void fillDefaults() {
        if (tenantId == null) {
            tenantId = 1L;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (success == null) {
            success = Boolean.FALSE;
        }
        if (rowCount == null) {
            rowCount = 0;
        }
        if (elapsedMs == null) {
            elapsedMs = 0L;
        }
    }
}


