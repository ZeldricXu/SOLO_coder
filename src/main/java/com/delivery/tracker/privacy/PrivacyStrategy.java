package com.delivery.tracker.privacy;

import java.util.Map;

/**
 * 差分隐私策略接口
 * 定义隐私保护的可插拔策略，支持运行时切换
 */
public interface PrivacyStrategy {

    /**
     * 获取策略名称
     */
    String getName();

    /**
     * 获取策略描述
     */
    String getDescription();

    /**
     * 对查询结果应用隐私保护
     *
     * @param queryResult 原始查询结果
     * @param config      隐私配置
     * @return 应用隐私保护后的结果
     */
    Map<String, Object> applyPrivacy(Map<String, Object> queryResult, PrivacyConfig config);

    /**
     * 计算本次查询消耗的隐私预算
     *
     * @param config 隐私配置
     * @return 消耗的epsilon值
     */
    double calculateEpsilonCost(PrivacyConfig config);

    /**
     * 判断是否支持该场景
     *
     * @param scene 场景标识
     */
    boolean supportsScene(String scene);
}
