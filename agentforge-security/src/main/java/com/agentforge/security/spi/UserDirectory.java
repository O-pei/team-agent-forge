package com.agentforge.security.spi;

import java.util.List;

/**
 * 只读用户目录。供其它模块展示用户信息或做角色判断，禁止跨模块直接使用 UserEntity/UserRepository。
 */
public interface UserDirectory {

    Page pageUsers(String keyword, int page, int size);

    UserBrief findUser(long userId);

    boolean hasRole(long userId, String roleCode);

    record UserBrief(Long id, String username, String email, String nickname, String status) {
    }

    record Page(List<UserBrief> records, long total, int page, int size) {
    }
}
