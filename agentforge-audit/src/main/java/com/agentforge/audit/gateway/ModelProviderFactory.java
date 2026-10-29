package com.agentforge.audit.gateway;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型工厂：由数据库中的模型配置解析出 Spring AI {@link ChatModel}。
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li>无可用配置或 provider=MOCK 时返回内置 Mock 模型，保证零配置可跑；</li>
 *   <li>密钥只在内存中解密使用，禁止打印；</li>
 *   <li>实现类由 agentforge-audit 提供，agent 模块只依赖本接口。</li>
 * </ul>
 */
public interface ModelProviderFactory {

    /**
     * @param modelId 模型配置 ID；{@code null} 表示默认模型
     */
    ChatModel getChatModel(Long modelId);

    ChatModel getDefaultChatModel();

    Long getDefaultModelId();

    ChatModelInfo getModelInfo(Long modelId);
}
