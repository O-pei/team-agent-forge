package com.agentforge.datasource.dto;

import java.util.List;

/**
 * 数据源结构（表 / 字段 / 注释 / 主键）。
 */
public record SchemaResponse(List<TableSchema> tables) {

    public record TableSchema(String name, String comment, List<ColumnSchema> columns) {
    }

    public record ColumnSchema(String name, String type, boolean nullable, boolean primaryKey, String comment) {
    }
}
