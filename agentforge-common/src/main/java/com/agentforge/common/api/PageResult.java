package com.agentforge.common.api;

import java.util.List;

/**
 * 通用分页结果。
 *
 * @param records 当前页数据
 * @param total   总记录数
 * @param page    当前页码（从 1 开始）
 * @param size    每页大小
 */
public record PageResult<T>(List<T> records, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(records == null ? List.of() : records, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    public long pages() {
        return size <= 0 ? 0 : (total + size - 1) / size;
    }
}
