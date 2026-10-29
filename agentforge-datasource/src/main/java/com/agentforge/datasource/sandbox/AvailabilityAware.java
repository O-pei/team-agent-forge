package com.agentforge.datasource.sandbox;

/**
 * 沙箱实现可用性标记（不属于冻结 SPI，仅用于 /providers 能力展示与选择）。
 */
public interface AvailabilityAware {

    /** 当前环境是否可用。 */
    boolean available();

    /** 支持的语言列表。 */
    java.util.List<String> supportedLanguages();

    /** 能力说明。 */
    String description();
}
