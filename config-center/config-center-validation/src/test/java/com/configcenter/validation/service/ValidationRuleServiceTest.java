package com.configcenter.validation.service;

import com.configcenter.common.exception.ConfigValidationException;
import com.configcenter.common.testdata.TestDataBuilder;
import com.configcenter.validation.config.ValidationProperties;
import com.configcenter.validation.rule.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("校验规则服务单元测试")
class ValidationRuleServiceTest {

    @Mock
    private ValidationProperties properties;

    @InjectMocks
    private ValidationRuleService ruleService;

    @BeforeEach
    void setUp() {
        when(properties.getRules()).thenReturn(new ArrayList<>());
        ruleService.init();
    }

    @Test
    @DisplayName("测试校验规则加载 - 正确加载默认规则")
    void testRuleLoading_DefaultRules() {
        Map<String, ValidationRule> rules = ruleService.getRules();

        assertNotNull(rules);
        assertEquals(5, rules.size());

        assertTrue(rules.containsKey("RULE_KEY_FORMAT"));
        assertTrue(rules.containsKey("RULE_VALUE_LENGTH"));
        assertTrue(rules.containsKey("RULE_JSON_FORMAT"));
        assertTrue(rules.containsKey("RULE_NUMBER_RANGE"));
        assertTrue(rules.containsKey("RULE_SENSITIVE_CHECK"));

        ValidationRule keyFormatRule = rules.get("RULE_KEY_FORMAT");
        assertEquals("KEY_FORMAT", keyFormatRule.getRuleType());
        assertEquals("配置键格式校验", keyFormatRule.getName());
        assertTrue(keyFormatRule.isEnabled());
    }

    @Test
    @DisplayName("测试KeyFormatRule - 有效配置键通过校验")
    void testKeyFormatRule_ValidKey() {
        KeyFormatRule rule = KeyFormatRule.builder().build();

        assertDoesNotThrow(() -> {
            rule.validate("valid.key.name_123", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("database.url", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("feature_flag_enabled", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试KeyFormatRule - 无效配置键抛出异常")
    void testKeyFormatRule_InvalidKey() {
        KeyFormatRule rule = KeyFormatRule.builder().build();

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("123invalid", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate(null, new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("invalid key with space", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试KeyFormatRule - 自定义正则表达式")
    void testKeyFormatRule_CustomPattern() {
        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "^[a-z]+$");

        KeyFormatRule rule = KeyFormatRule.builder()
                .params(params)
                .build();

        assertDoesNotThrow(() -> {
            rule.validate("lowercaseonly", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("MixedCase", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("with.period", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试ValueLengthRule - 长度在有效范围内")
    void testValueLengthRule_ValidLength() {
        Map<String, Object> params = new HashMap<>();
        params.put("minLength", 1);
        params.put("maxLength", 100);

        ValueLengthRule rule = ValueLengthRule.builder()
                .params(params)
                .build();

        assertDoesNotThrow(() -> {
            rule.validate("short", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("a", new HashMap<>());
        });

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("a");
        }
        assertDoesNotThrow(() -> {
            rule.validate(sb.toString(), new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试ValueLengthRule - 长度超出范围抛出异常")
    void testValueLengthRule_InvalidLength() {
        Map<String, Object> params = new HashMap<>();
        params.put("minLength", 5);
        params.put("maxLength", 10);

        ValueLengthRule rule = ValueLengthRule.builder()
                .params(params)
                .build();

        ConfigValidationException ex1 = assertThrows(ConfigValidationException.class, () -> {
            rule.validate("abc", new HashMap<>());
        });
        assertTrue(ex1.getMessage().contains("不能小于"));

        StringBuilder longString = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            longString.append("a");
        }
        ConfigValidationException ex2 = assertThrows(ConfigValidationException.class, () -> {
            rule.validate(longString.toString(), new HashMap<>());
        });
        assertTrue(ex2.getMessage().contains("不能超过"));

        ConfigValidationException ex3 = assertThrows(ConfigValidationException.class, () -> {
            rule.validate(null, new HashMap<>());
        });
        assertTrue(ex3.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("测试JsonFormatRule - 有效JSON通过校验")
    void testJsonFormatRule_ValidJson() {
        JsonFormatRule rule = JsonFormatRule.builder().build();

        assertDoesNotThrow(() -> {
            rule.validate("{\"key\": \"value\"}", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("[1, 2, 3]", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("\"string\"", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("123", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("true", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试JsonFormatRule - 无效JSON抛出异常")
    void testJsonFormatRule_InvalidJson() {
        JsonFormatRule rule = JsonFormatRule.builder().build();

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("{\"key\": value}", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("{key: value}", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("not json", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试JsonFormatRule - allowEmpty参数")
    void testJsonFormatRule_AllowEmpty() {
        Map<String, Object> params = new HashMap<>();
        params.put("allowEmpty", true);

        JsonFormatRule allowEmptyRule = JsonFormatRule.builder()
                .params(params)
                .build();

        assertDoesNotThrow(() -> {
            allowEmptyRule.validate("", new HashMap<>());
        });

        Map<String, Object> params2 = new HashMap<>();
        params2.put("allowEmpty", false);

        JsonFormatRule notAllowEmptyRule = JsonFormatRule.builder()
                .params(params2)
                .build();

        assertThrows(ConfigValidationException.class, () -> {
            notAllowEmptyRule.validate("", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试NumberRangeRule - 数值在有效范围内")
    void testNumberRangeRule_ValidNumber() {
        Map<String, Object> params = new HashMap<>();
        params.put("min", 0);
        params.put("max", 100);

        NumberRangeRule rule = NumberRangeRule.builder()
                .params(params)
                .build();

        assertDoesNotThrow(() -> {
            rule.validate("0", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("50", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("100", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("3.14", new HashMap<>());
        });
        assertDoesNotThrow(() -> {
            rule.validate("-10", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试NumberRangeRule - 数值超出范围抛出异常")
    void testNumberRangeRule_InvalidNumber() {
        Map<String, Object> params = new HashMap<>();
        params.put("min", 10);
        params.put("max", 100);

        NumberRangeRule rule = NumberRangeRule.builder()
                .params(params)
                .build();

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("5", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("150", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("not a number", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试SensitiveCheckRule - warnOnly模式记录警告")
    void testSensitiveCheckRule_WarnOnlyMode() {
        Map<String, Object> params = new HashMap<>();
        params.put("warnOnly", true);
        params.put("keywords", Arrays.asList("password", "secret"));

        SensitiveCheckRule rule = SensitiveCheckRule.builder()
                .params(params)
                .build();

        Map<String, Object> context = new HashMap<>();

        assertDoesNotThrow(() -> {
            rule.validate("this is a password test", context);
        });

        assertTrue(context.containsKey("warning_RULE_SENSITIVE_CHECK"));
        assertTrue(context.get("warning_RULE_SENSITIVE_CHECK").toString().contains("password"));
    }

    @Test
    @DisplayName("测试SensitiveCheckRule - 非warnOnly模式抛出异常")
    void testSensitiveCheckRule_ErrorMode() {
        Map<String, Object> params = new HashMap<>();
        params.put("warnOnly", false);
        params.put("keywords", Arrays.asList("password", "secret", "token"));

        SensitiveCheckRule rule = SensitiveCheckRule.builder()
                .params(params)
                .build();

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("this contains my secret key", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("database password is 123456", new HashMap<>());
        });

        assertThrows(ConfigValidationException.class, () -> {
            rule.validate("bearer token xxx", new HashMap<>());
        });

        assertDoesNotThrow(() -> {
            rule.validate("this is normal text", new HashMap<>());
        });
    }

    @Test
    @DisplayName("测试不同校验规则的行为差异")
    void testRuleBehaviorDifferences() {
        String validJson = "{\"key\": \"value\"}";
        String invalidJson = "{invalid}";
        String validNumber = "42";
        String invalidNumber = "abc";

        JsonFormatRule jsonRule = JsonFormatRule.builder().build();
        NumberRangeRule numberRule = NumberRangeRule.builder().build();

        assertDoesNotThrow(() -> jsonRule.validate(validJson, new HashMap<>()));
        assertThrows(ConfigValidationException.class, () -> jsonRule.validate(invalidJson, new HashMap<>()));
        assertDoesNotThrow(() -> numberRule.validate(validNumber, new HashMap<>()));
        assertThrows(ConfigValidationException.class, () -> numberRule.validate(invalidNumber, new HashMap<>()));
    }

    @Test
    @DisplayName("测试多规则链式校验")
    void testValidateWithRules_MultipleRules() {
        String validValue = "{\"timeout\": 30}";
        String invalidJson = "{invalid";
        String invalidRange = "{\"timeout\": 500}";

        List<String> ruleIds = Arrays.asList("RULE_JSON_FORMAT", "RULE_VALUE_LENGTH");

        Map<String, Object> result1 = ruleService.validateWithRules(validValue, ruleIds, new HashMap<>());
        assertTrue((Boolean) result1.get("success"));
        assertTrue(((List<?>) result1.get("errors")).isEmpty());
        assertEquals(2, ((List<?>) result1.get("passedRules")).size());

        Map<String, Object> result2 = ruleService.validateWithRules(invalidJson, ruleIds, new HashMap<>());
        assertFalse((Boolean) result2.get("success"));
        assertFalse(((List<?>) result2.get("errors")).isEmpty());
    }

    @Test
    @DisplayName("测试校验失败时的错误提示准确性")
    void testErrorMessageAccuracy() {
        KeyFormatRule keyRule = KeyFormatRule.builder().build();

        try {
            keyRule.validate("123invalid", new HashMap<>());
            fail("应该抛出异常");
        } catch (ConfigValidationException e) {
            assertTrue(e.getMessage().contains("正则"));
            assertTrue(e.getMessage().contains("配置键格式错误"));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("minLength", 10);
        params.put("maxLength", 20);
        ValueLengthRule lengthRule = ValueLengthRule.builder().params(params).build();

        try {
            lengthRule.validate("short", new HashMap<>());
            fail("应该抛出异常");
        } catch (ConfigValidationException e) {
            assertTrue(e.getMessage().contains("不能小于 10"));
        }

        try {
            lengthRule.validate("this is a very long string that exceeds the limit", new HashMap<>());
            fail("应该抛出异常");
        } catch (ConfigValidationException e) {
            assertTrue(e.getMessage().contains("不能超过 20"));
        }

        NumberRangeRule numberRule = NumberRangeRule.builder()
                .params(new HashMap<String, Object>() {{
                    put("min", 0);
                    put("max", 100);
                }})
                .build();

        try {
            numberRule.validate("150", new HashMap<>());
            fail("应该抛出异常");
        } catch (ConfigValidationException e) {
            assertTrue(e.getMessage().contains("不能超过 100"));
        }
    }

    @Test
    @DisplayName("测试规则启用/禁用功能")
    void testRuleEnableDisable() {
        String ruleId = "RULE_KEY_FORMAT";

        assertTrue(ruleService.getRule(ruleId).isEnabled());

        ruleService.disableRule(ruleId);
        assertFalse(ruleService.getRule(ruleId).isEnabled());

        ruleService.enableRule(ruleId);
        assertTrue(ruleService.getRule(ruleId).isEnabled());
    }

    @Test
    @DisplayName("测试规则统计信息")
    void testRuleStatistics() {
        Map<String, Object> stats = ruleService.getRuleStatistics();

        assertEquals(5, stats.get("totalRules"));
        assertEquals(5, stats.get("enabledRules"));
        assertEquals(0, stats.get("disabledRules"));

        ruleService.disableRule("RULE_SENSITIVE_CHECK");
        stats = ruleService.getRuleStatistics();
        assertEquals(4, stats.get("enabledRules"));
        assertEquals(1, stats.get("disabledRules"));
    }

    @Test
    @DisplayName("测试单规则校验")
    void testValidateWithSingleRule() {
        Map<String, Object> result1 = ruleService.validateWithRule("valid.key", "RULE_KEY_FORMAT");
        assertTrue((Boolean) result1.get("success"));

        Map<String, Object> result2 = ruleService.validateWithRule("123invalid", "RULE_KEY_FORMAT");
        assertFalse((Boolean) result2.get("success"));
        assertNotNull(result2.get("errorMessage"));
    }

    @Test
    @DisplayName("测试不存在的规则抛出异常")
    void testNonExistentRule() {
        assertThrows(ConfigValidationException.class, () -> {
            ruleService.validateWithRule("test", "NON_EXISTENT_RULE");
        });
    }
}
