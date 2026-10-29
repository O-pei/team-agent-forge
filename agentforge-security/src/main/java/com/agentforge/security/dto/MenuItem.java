package com.agentforge.security.dto;

/**
 * 左侧菜单项，按当前用户权限过滤生成；icon 为前端图标名，permission 为展示所需权限码。
 */
public record MenuItem(
        String code,
        String name,
        String path,
        String icon,
        String permission) {
}
