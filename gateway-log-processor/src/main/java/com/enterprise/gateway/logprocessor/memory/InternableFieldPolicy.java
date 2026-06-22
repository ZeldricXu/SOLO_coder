package com.enterprise.gateway.logprocessor.memory;

import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

/**
 * 策略类，用于决定哪些日志字段需要进行字符串池化（String Interning）。
 *
 * <p>池化可以减少内存占用，特别是对于重复出现的低基数字段。
 * 但对于高基数或过长的字符串，池化反而会增加内存开销。</p>
 *
 * <p>该类采用单例模式设计，确保全局只有一个策略实例。</p>
 *
 * @author Gateway Team
 * @since 1.0.0
 */
public final class InternableFieldPolicy {

    private static final int MAX_LENGTH_FOR_INTERN = 64;

    private static final InternableFieldPolicy INSTANCE = new InternableFieldPolicy();

    private final Set<String> alwaysInternFields;
    private final Set<String> neverInternFields;

    private InternableFieldPolicy() {
        Set<String> always = new HashSet<>();
        always.add("service");
        always.add("level");
        always.add("statusCode");
        always.add("method");
        this.alwaysInternFields = Collections.unmodifiableSet(always);

        Set<String> never = new HashSet<>();
        never.add("message");
        never.add("traceId");
        never.add("path");
        this.neverInternFields = Collections.unmodifiableSet(never);
    }

    /**
     * 获取策略类的单例实例。
     *
     * @return InternableFieldPolicy 的单例实例
     */
    public static InternableFieldPolicy getInstance() {
        return INSTANCE;
    }

    /**
     * 判断指定的字段值是否应该进行字符串池化。
     *
     * <p>判断逻辑：</p>
     * <ol>
     *   <li>如果字段在"总是池化"列表中，返回 {@code true}</li>
     *   <li>如果字段在"永不池化"列表中，返回 {@code false}</li>
     *   <li>如果值的长度超过 64，返回 {@code false}（太长不值得池化）</li>
     *   <li>其他情况根据估计的基数决定：低基数字段优先池化</li>
     * </ol>
     *
     * @param fieldName 字段名称，不能为空
     * @param value 字段值，可以为 null
     * @param estimatedCardinality 估计的字段基数（唯一值数量）
     * @return 如果应该池化返回 {@code true}，否则返回 {@code false}
     * @throws IllegalArgumentException 如果 fieldName 为 null 或空
     */
    public boolean shouldIntern(String fieldName, String value, int estimatedCardinality) {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must not be null or empty");
        }

        if (value == null) {
            return false;
        }

        if (alwaysInternFields.contains(fieldName)) {
            return true;
        }

        if (neverInternFields.contains(fieldName)) {
            return false;
        }

        if (value.length() > MAX_LENGTH_FOR_INTERN) {
            return false;
        }

        return estimatedCardinality < 1000;
    }

    /**
     * 简化版本的判断方法，使用默认基数估计值。
     *
     * @param fieldName 字段名称，不能为空
     * @param value 字段值，可以为 null
     * @return 如果应该池化返回 {@code true}，否则返回 {@code false}
     * @see #shouldIntern(String, String, int)
     */
    public boolean shouldIntern(String fieldName, String value) {
        return shouldIntern(fieldName, value, Integer.MAX_VALUE);
    }

    /**
     * 获取"总是池化"的字段集合。
     *
     * @return 不可修改的字段名集合
     */
    public Set<String> getAlwaysInternFields() {
        return alwaysInternFields;
    }

    /**
     * 获取"永不池化"的字段集合。
     *
     * @return 不可修改的字段名集合
     */
    public Set<String> getNeverInternFields() {
        return neverInternFields;
    }
}
