package com.agentforge.agent.runtime;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;

/**
 * 把底层 {@link ChatModel} 的默认 {@link ChatOptions} 换成 Jackson 可序列化的实现。
 *
 * <p>SAA 的 {@code DefaultBuilder} 构建 ReactAgent 时会用
 * {@code ModelOptionsUtils.merge} 合并 ChatOptions；Spring AI 的
 * {@code DefaultChatOptions} 没有 {@code @JsonProperty} 字段，合并时会抛
 * {@code IllegalArgumentException: No @JsonProperty fields found}（Mock 模型的默认选项就是该类）。
 * 这里用 {@link DashScopeChatOptions}（Jackson 可序列化）作为默认选项，调用委托给真实模型，
 * 保证工具调用链（ReactAgent）能正常构建。</p>
 */
public class JacksonSafeChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatOptions defaultOptions;

    public JacksonSafeChatModel(ChatModel delegate) {
        this.delegate = delegate;
        this.defaultOptions = buildDefaultOptions(delegate);
    }

    /** 底层模型是否已经提供了 Jackson 可序列化的 ChatOptions。 */
    public static boolean hasJacksonAnnotatedOptions(ChatOptions options) {
        if (options == null) {
            return false;
        }
        Class<?> type = options.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(JsonProperty.class)) {
                    return true;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /** 必要时包装，否则原样返回。 */
    public static ChatModel wrapIfNecessary(ChatModel model) {
        if (model == null || model instanceof JacksonSafeChatModel) {
            return model;
        }
        try {
            if (hasJacksonAnnotatedOptions(model.getDefaultOptions())) {
                return model;
            }
        } catch (RuntimeException ignored) {
            // 取不到默认选项时按需要包装处理
        }
        return new JacksonSafeChatModel(model);
    }

    private static ChatOptions buildDefaultOptions(ChatModel delegate) {
        DashScopeChatOptions options = new DashScopeChatOptions();
        try {
            ChatOptions origin = delegate.getDefaultOptions();
            if (origin != null && origin.getModel() != null) {
                options.setModel(origin.getModel());
            }
        } catch (RuntimeException ignored) {
            // 忽略，使用默认模型名
        }
        return options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public String toString() {
        return "JacksonSafeChatModel[" + delegate + "]";
    }
}
