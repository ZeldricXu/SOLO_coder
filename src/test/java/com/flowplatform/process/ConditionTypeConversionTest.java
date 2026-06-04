package com.flowplatform.process;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.flowplatform.common.ProcessEngine;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("条件表达式类型转换测试")
public class ConditionTypeConversionTest extends BaseUnitTest {

    private final ProcessEngine engine = new ProcessEngine();

    private JSONObject buildFormSchema(Object... fields) {
        JSONObject schema = new JSONObject();
        JSONArray fieldArray = new JSONArray();
        for (int i = 0; i < fields.length; i += 3) {
            JSONObject field = new JSONObject();
            field.put("key", fields[i]);
            field.put("type", fields[i + 1]);
            field.put("label", fields[i + 2]);
            fieldArray.add(field);
        }
        schema.put("fields", fieldArray);
        return schema;
    }

    @Test
    @DisplayName("数字字段比较 - 字符串值转数字")
    void testNumberFieldStringComparison() {
        JSONObject schema = buildFormSchema(
                "amount", "number", "报销金额"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", "999");

        boolean result = engine.evaluateCondition("${amount} > 1000", formData, schema);
        assertFalse(result, "字符串'999'应转为数字999，不大于1000");

        formData.put("amount", "1001");
        result = engine.evaluateCondition("${amount} > 1000", formData, schema);
        assertTrue(result, "字符串'1001'应转为数字1001，大于1000");
    }

    @Test
    @DisplayName("数字字段比较 - 浮点数值")
    void testNumberFieldFloatComparison() {
        JSONObject schema = buildFormSchema(
                "amount", "amount", "报销金额"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", "999.99");

        boolean result = engine.evaluateCondition("${amount} <= 1000", formData, schema);
        assertTrue(result, "999.99 <= 1000 应为 true");

        formData.put("amount", 1000.01);
        result = engine.evaluateCondition("${amount} <= 1000", formData, schema);
        assertFalse(result, "1000.01 <= 1000 应为 false");
    }

    @Test
    @DisplayName("字符串字段相等比较 - 带引号去掉")
    void testStringFieldQuotedComparison() {
        JSONObject schema = buildFormSchema(
                "department", "select", "部门"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("department", "技术部");

        boolean result = engine.evaluateCondition("${department} == '技术部'", formData, schema);
        assertTrue(result, "字符串比较应正确，引号应被去掉");

        formData.put("department", "产品部");
        result = engine.evaluateCondition("${department} == '技术部'", formData, schema);
        assertFalse(result, "部门不同应返回false");
    }

    @Test
    @DisplayName("多条件AND - 混合数字和字符串")
    void testMixedTypeAndCondition() {
        JSONObject schema = buildFormSchema(
                "amount", "number", "报销金额",
                "department", "select", "部门"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", "1500");
        formData.put("department", "技术部");

        boolean result = engine.evaluateCondition(
                "${amount} > 1000 AND ${department} == '技术部'",
                formData, schema);
        assertTrue(result, "1500>1000 AND 技术部==技术部 应为 true");

        formData.put("amount", "999");
        result = engine.evaluateCondition(
                "${amount} > 1000 AND ${department} == '技术部'",
                formData, schema);
        assertFalse(result, "999>1000 为false，整体AND为false");

        formData.put("amount", "1500");
        formData.put("department", "产品部");
        result = engine.evaluateCondition(
                "${amount} > 1000 AND ${department} == '技术部'",
                formData, schema);
        assertFalse(result, "部门不匹配，整体AND为false");
    }

    @Test
    @DisplayName("多条件OR - 混合数字和字符串")
    void testMixedTypeOrCondition() {
        JSONObject schema = buildFormSchema(
                "amount", "number", "报销金额",
                "urgent", "radio", "是否紧急"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", "500");
        formData.put("urgent", "是");

        boolean result = engine.evaluateCondition(
                "${amount} > 1000 OR ${urgent} == '是'",
                formData, schema);
        assertTrue(result, "金额不足但紧急，OR应为true");

        formData.put("urgent", "否");
        result = engine.evaluateCondition(
                "${amount} > 1000 OR ${urgent} == '是'",
                formData, schema);
        assertFalse(result, "都不满足应为false");
    }

    @Test
    @DisplayName("日期字段比较")
    void testDateFieldComparison() {
        JSONObject schema = buildFormSchema(
                "startDate", "date", "开始日期"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("startDate", "2025-06-15");

        boolean result = engine.evaluateCondition(
                "${startDate} > '2025-06-01'",
                formData, schema);
        assertTrue(result, "2025-06-15 > 2025-06-01 应为 true");

        result = engine.evaluateCondition(
                "${startDate} < '2025-06-01'",
                formData, schema);
        assertFalse(result, "2025-06-15 < 2025-06-01 应为 false");
    }

    @Test
    @DisplayName("未知字段类型 - 回退到字符串比较并记录警告")
    void testUnknownFieldTypeFallback() {
        JSONObject schema = buildFormSchema();

        Map<String, Object> formData = new HashMap<>();
        formData.put("unknownField", "123");

        boolean result = engine.evaluateCondition("${unknownField} == '123'", formData, schema);
        assertTrue(result, "未知类型应回退到字符串比较");

        result = engine.evaluateCondition("${unknownField} > 100", formData, schema);
        assertFalse(result, "未知类型不应用数字比较");
    }

    @Test
    @DisplayName(">= <= 比较 - 边界值")
    void testBoundaryComparisons() {
        JSONObject schema = buildFormSchema(
                "days", "number", "天数"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("days", "3");

        assertTrue(engine.evaluateCondition("${days} >= 3", formData, schema));
        assertTrue(engine.evaluateCondition("${days} <= 3", formData, schema));
        assertTrue(engine.evaluateCondition("${days} > 2", formData, schema));
        assertTrue(engine.evaluateCondition("${days} < 4", formData, schema));
    }

    @Test
    @DisplayName("!= 不等于比较")
    void testNotEqualsComparison() {
        JSONObject schema = buildFormSchema(
                "status", "select", "状态",
                "amount", "number", "金额"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("status", "已提交");
        formData.put("amount", "100");

        assertTrue(engine.evaluateCondition("${status} != '草稿'", formData, schema));
        assertFalse(engine.evaluateCondition("${status} != '已提交'", formData, schema));
        assertTrue(engine.evaluateCondition("${amount} != 200", formData, schema));
        assertFalse(engine.evaluateCondition("${amount} != 100", formData, schema));
    }

    @Test
    @DisplayName("空值处理")
    void testNullValueHandling() {
        JSONObject schema = buildFormSchema(
                "comment", "textarea", "备注"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("comment", null);

        boolean result = engine.evaluateCondition("${comment} == ''", formData, schema);
        assertTrue(result, "null值应转为空字符串");

        assertFalse(engine.evaluateCondition("${comment} != ''", formData, schema));
    }

    @Test
    @DisplayName("金额字段小数比较")
    void testAmountFieldDecimalComparison() {
        JSONObject schema = buildFormSchema(
                "total", "amount", "总金额"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("total", "5000.00");

        assertTrue(engine.evaluateCondition("${total} >= 5000", formData, schema));
        assertFalse(engine.evaluateCondition("${total} > 5000", formData, schema));

        formData.put("total", "5000.01");
        assertTrue(engine.evaluateCondition("${total} > 5000", formData, schema));
    }

    @Test
    @DisplayName("大小写不敏感AND/OR")
    void testCaseInsensitiveOperators() {
        JSONObject schema = buildFormSchema(
                "amount", "number", "金额",
                "dept", "select", "部门"
        );

        Map<String, Object> formData = new HashMap<>();
        formData.put("amount", "2000");
        formData.put("dept", "技术部");

        assertTrue(engine.evaluateCondition(
                "${amount} > 1000 and ${dept} == '技术部'", formData, schema));

        assertTrue(engine.evaluateCondition(
                "${amount} > 1000 OR ${dept} == '市场部'", formData, schema));

        assertTrue(engine.evaluateCondition(
                "${amount} > 1000 And ${dept} == '技术部'", formData, schema));
    }
}
