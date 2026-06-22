package com.enterprise.gateway.logprocessor.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InternableFieldPolicyTest {

    private InternableFieldPolicy policy;

    @BeforeEach
    void setUp() {
        policy = InternableFieldPolicy.getInstance();
    }

    @Test
    @DisplayName("service, level, statusCode, method 应始终被池化")
    void testAlwaysInternFields() {
        assertTrue(policy.shouldIntern("service", "api-gateway"));
        assertTrue(policy.shouldIntern("level", "INFO"));
        assertTrue(policy.shouldIntern("statusCode", "200"));
        assertTrue(policy.shouldIntern("method", "GET"));

        assertTrue(policy.shouldIntern("service", "api-gateway", 1000000));
        assertTrue(policy.shouldIntern("level", "ERROR", 1000000));
        assertTrue(policy.shouldIntern("statusCode", "500", 1000000));
        assertTrue(policy.shouldIntern("method", "POST", 1000000));

        String longService = "a".repeat(64);
        assertTrue(policy.shouldIntern("service", longService));
    }

    @Test
    @DisplayName("message, traceId, path 不应被池化")
    void testNeverInternFields() {
        assertFalse(policy.shouldIntern("message", "This is a log message"));
        assertFalse(policy.shouldIntern("traceId", "abc123def456"));
        assertFalse(policy.shouldIntern("path", "/api/v1/users"));

        assertFalse(policy.shouldIntern("message", "short", 0));
        assertFalse(policy.shouldIntern("traceId", "abc", 0));
        assertFalse(policy.shouldIntern("path", "/", 0));
    }

    @Test
    @DisplayName("长度限制为64字符")
    void testLengthLimit() {
        String exactly64 = "a".repeat(64);
        assertTrue(policy.shouldIntern("otherField", exactly64, 100));

        String exactly65 = "a".repeat(65);
        assertFalse(policy.shouldIntern("otherField", exactly65, 100));

        assertTrue(policy.shouldIntern("service", exactly65));
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testArgumentValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                policy.shouldIntern(null, "value"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.shouldIntern("", "value"));
    }

    @Test
    @DisplayName("null 值不应被池化")
    void testNullValue() {
        assertFalse(policy.shouldIntern("service", null));
        assertFalse(policy.shouldIntern("level", null, 100));
        assertFalse(policy.shouldIntern("other", null, 100));
    }

    @Test
    @DisplayName("其他字段根据基数判断")
    void testOtherFieldsByCardinality() {
        assertTrue(policy.shouldIntern("host", "server-01", 500));
        assertTrue(policy.shouldIntern("host", "server-01", 999));

        assertFalse(policy.shouldIntern("host", "server-01", 1000));
        assertFalse(policy.shouldIntern("host", "server-01", 10000));
    }

    @Test
    @DisplayName("简化版本的 shouldIntern 使用默认基数")
    void testSimplifiedShouldIntern() {
        assertFalse(policy.shouldIntern("otherField", "value"));

        assertTrue(policy.shouldIntern("service", "api"));
        assertFalse(policy.shouldIntern("message", "test"));
    }

    @Test
    @DisplayName("getAlwaysInternFields 和 getNeverInternFields 返回正确集合")
    void testGetInternFieldSets() {
        Set<String> always = policy.getAlwaysInternFields();
        assertTrue(always.contains("service"));
        assertTrue(always.contains("level"));
        assertTrue(always.contains("statusCode"));
        assertTrue(always.contains("method"));
        assertEquals(4, always.size());

        Set<String> never = policy.getNeverInternFields();
        assertTrue(never.contains("message"));
        assertTrue(never.contains("traceId"));
        assertTrue(never.contains("path"));
        assertEquals(3, never.size());
    }

    @Test
    @DisplayName("返回的集合不可修改")
    void testReturnedSetsImmutable() {
        Set<String> always = policy.getAlwaysInternFields();
        assertThrows(UnsupportedOperationException.class, () ->
                always.add("newField"));

        Set<String> never = policy.getNeverInternFields();
        assertThrows(UnsupportedOperationException.class, () ->
                never.remove("message"));
    }

    @Test
    @DisplayName("单例模式验证")
    void testSingleton() {
        InternableFieldPolicy instance1 = InternableFieldPolicy.getInstance();
        InternableFieldPolicy instance2 = InternableFieldPolicy.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("空字符串值的处理")
    void testEmptyStringValue() {
        assertTrue(policy.shouldIntern("service", ""));
        assertFalse(policy.shouldIntern("otherField", "", 100));
    }

    @Test
    @DisplayName("字段名大小写敏感")
    void testFieldNameCaseSensitive() {
        assertTrue(policy.shouldIntern("service", "api"));
        assertFalse(policy.shouldIntern("Service", "api"));
        assertFalse(policy.shouldIntern("SERVICE", "api"));
    }
}
