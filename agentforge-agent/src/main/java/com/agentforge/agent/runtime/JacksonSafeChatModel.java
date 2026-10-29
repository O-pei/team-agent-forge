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
 * 包装底层 ChatModel，提供 Jackson 可序列化的默认 ChatOptions。
 * SAA 构建 ReactAgent 时会用 ModelOptionsUtils.merge 合并 ChatOptions，
 * Spring AI 的 DefaultChatOptions 没有 JsonProperty 字段，合并会抛
 * IllegalArgumentException，导致 Mock 模型无法构建 ReactAgent；
 * 这里统一用 DashScopeChatOptions 作为默认选项，模型调用仍委托底层实现。
 */
public class JacksonSafeChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatOptions defaultOptions;

    public JacksonSafeChatModel(ChatModel delegate) {
        this.delegate = delegate;
        this.defaultOptions = buildDefaultOptions(delegate);
    }

    /** 判断选项类是否已有 JsonProperty 字段，可被 SAA 合并。 */
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
