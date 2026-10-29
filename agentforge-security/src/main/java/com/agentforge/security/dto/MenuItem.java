package com.agentforge.security.dto;

/**
 * 左侧菜单项，按当前用户权限过滤生成。
 */
public record MenuItem(
        String code,
        String name,
        String path) {
}
