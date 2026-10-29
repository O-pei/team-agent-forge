package com.agentforge.common.exception;

import com.agentforge.common.api.ErrorCode;

/**
 * Token 额度不足。预检或运行中由额度策略抛出，对前端统一返回 42901。
 */
public class QuotaExceededException extends BizException {

    public QuotaExceededException(String message) {
        super(ErrorCode.QUOTA_EXCEEDED, message);
    }
}
