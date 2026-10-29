package com.agentforge.agent.runtime;

import com.agentforge.common.exception.QuotaExceededException;

/**
 * 从异常链中提取额度异常。模型调用可能发生在 Reactor 或 SAA 的包装层内部，
 * 额度错误必须原样上抛为 42901，不能被统一包装成 50000。
 */
public final class QuotaErrors {

    private QuotaErrors() {
    }

    public static QuotaExceededException find(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof QuotaExceededException quota) {
                return quota;
            }
            cursor = cursor.getCause();
        }
        return null;
    }
}
