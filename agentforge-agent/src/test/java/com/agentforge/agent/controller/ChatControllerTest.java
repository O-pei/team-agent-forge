package com.agentforge.agent.controller;

import com.agentforge.agent.dto.ChatStreamRequest;
import com.agentforge.agent.service.AgentChatService;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import com.agentforge.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不依赖 Mockito：用匿名子类替身覆盖 chat 行为，用动态代理桩掉 ObjectProvider。
 */
class ChatControllerTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesToServiceAndReturnsEmitter() {
        SseEmitter emitter = new SseEmitter();
        AgentChatService service = new AgentChatService(null, null, null) {
            @Override
            public SseEmitter chat(ChatStreamRequest request) {
                return emitter;
            }
        };
        ChatController controller = new ChatController(service, providerStub(null));

        assertThat(controller.stream(new ChatStreamRequest(1L, null, "你好", null))).isSameAs(emitter);
    }

    @Test
    void quotaPrecheckFailsBeforeSseStarts() {
        authenticate(7L);
        AtomicBoolean chatCalled = new AtomicBoolean();
        AgentChatService service = new AgentChatService(null, null, null) {
            @Override
            public SseEmitter chat(ChatStreamRequest request) {
                chatCalled.set(true);
                return new SseEmitter();
            }
        };
        ChatController controller = new ChatController(service,
                providerStub(com.agentforge.agent.support.QuotaServiceStubs.alwaysExceeded()));

        assertThatThrownBy(() -> controller.stream(new ChatStreamRequest(1L, null, "你好", null)))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(error -> assertThat(((QuotaExceededException) error).getCode()).isEqualTo(42901));
        assertThat(chatCalled).isFalse();
    }

    @Test
    void surfacesServiceValidationError() {
        AgentChatService service = new AgentChatService(null, null, null) {
            @Override
            public SseEmitter chat(ChatStreamRequest request) {
                throw BizException.badRequest("agentId 不能为空");
            }
        };
        ChatController controller = new ChatController(service, providerStub(null));

        assertThatThrownBy(() -> controller.stream(new ChatStreamRequest(null, null, "hi", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("agentId");
    }

    private void authenticate(Long userId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, "admin", 1L, Set.of("ADMIN"),
                Set.of("agent:run"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("agent:run"))));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerStub(T bean) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getIfAvailable", "getIfUnique", "getObject" -> bean;
            case "toString" -> "ObjectProviderStub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (ObjectProvider<T>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ObjectProvider.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        return '\0';
    }
}
