package com.agentforge.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 获取当前时间。
 */
@Component
public class CurrentTimeTool implements AgentToolDefinition {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    @Override
    public String name() {
        return ToolNames.CURRENT_TIME;
    }

    @Override
    public String description() {
        return "获取指定时区的当前日期时间";
    }

    @Override
    public String riskLevel() {
        return "LOW";
    }

    @Override
    public Object toolBean() {
        return this;
    }

    @Tool(name = ToolNames.CURRENT_TIME, description = "获取当前日期时间，可指定时区（如 Asia/Shanghai），默认系统时区")
    public String currentTime(
            @ToolParam(description = "IANA 时区 ID，例如 Asia/Shanghai；不传则用系统默认时区", required = false) String zone) {
        try {
            ZoneId zoneId = (zone == null || zone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
            return ZonedDateTime.now(zoneId).format(FORMATTER);
        } catch (RuntimeException e) {
            return ZonedDateTime.now().format(FORMATTER) + "（时区 " + zone + " 无法识别，已使用系统默认时区）";
        }
    }
}
