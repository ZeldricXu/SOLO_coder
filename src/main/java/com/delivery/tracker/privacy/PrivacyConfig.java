package com.delivery.tracker.privacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 差分隐私配置
 * 支持动态配置，运行时热更新
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrivacyConfig implements Serializable {

    /**
     * 配置ID
     */
    private String configId;

    /**
     * 场景标识
     */
    private String scene;

    /**
     * 策略名称
     */
    private String strategyName;

    /**
     * 敏感度
     */
    private double sensitivity;

    /**
     * Epsilon值（隐私预算）
     */
    private double epsilon;

    /**
     * Delta值
     */
    private double delta;

    /**
     * 噪声分布类型：LAPLACE, GAUSSIAN
     */
    private String noiseDistribution;

    /**
     * 噪声尺度因子
     */
    private double scaleFactor;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 优先级（数值越大优先级越高）
     */
    private int priority;

    /**
     * 扩展参数
     */
    private Map<String, Object> parameters;

    /**
     * 版本号，用于热更新检测
     */
    private long version;

    /**
     * 配置描述
     */
    private String description;
}
