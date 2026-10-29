package com.agentforge.audit.gateway;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型工厂接口：按数据库中的模型配置解析出 Spring AI ChatModel。
 * 无可用配置或 provider 为 MOCK 时返回内置 Mock 模型，保证零配置可跑；
 * 密钥只在内存中解密使用，禁止打印；实现由 agentforge-audit 提供，agent 模块只依赖本接口。
 */
public interface ModelProviderFactory {

    ChatModel getChatModel(Long modelId);

    ChatModel getDefaultChatModel();

    Long getDefaultModelId();

    ChatModelInfo getModelInfo(Long modelId);
}
