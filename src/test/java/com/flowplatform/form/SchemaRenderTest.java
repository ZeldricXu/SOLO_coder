package com.flowplatform.form;

import com.flowplatform.common.SchemaRenderer;
import com.flowplatform.common.renderer.*;
import com.flowplatform.test.BaseUnitTest;
import com.flowplatform.test.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("表单Schema渲染引擎测试")
public class SchemaRenderTest extends BaseUnitTest {

    private SchemaRenderer renderer;

    @BeforeEach
    void setUp() {
        RendererRegistry registry = new RendererRegistry(List.of(
                new TextFieldRenderer(),
                new TextareaFieldRenderer(),
                new NumberFieldRenderer(),
                new AmountFieldRenderer(),
                new EmailFieldRenderer(),
                new PhoneFieldRenderer(),
                new SelectFieldRenderer(),
                new RadioFieldRenderer(),
                new CheckboxFieldRenderer(),
                new DatePickerRenderer(),
                new DateTimePickerRenderer(),
                new DateRangeRenderer(),
                new FileUploadRenderer(),
                new SignatureRenderer(),
                new SubFormRenderer(),
                new FormulaRenderer(),
                new DefaultFieldRenderer()
        ));
        renderer = new SchemaRenderer(registry);
    }

    @Test
    @DisplayName("单行文本字段渲染测试")
    public void testTextInputRender() {
        String html = renderer.render(TestDataFactory.simpleTextSchema());
        assertFalse(html.isEmpty(), "渲染结果不应为空");
        assertTrue(html.contains("data-field-type=\"textInput\""), "应包含文本输入字段类型");
        assertTrue(html.contains("data-field-key=\"field_name\""), "应包含姓名字段key");
        assertTrue(html.contains("required"), "必填字段应有required属性");
        assertTrue(html.contains("placeholder=\"请输入姓名\""), "应有占位符文本");
        assertTrue(renderer.getErrors().isEmpty(), "不应有渲染错误");
    }

    @Test
    @DisplayName("下拉选择字段渲染测试")
    public void testSelectRender() {
        String html = renderer.render(TestDataFactory.selectSchema());
        assertTrue(html.contains("<select name=\"field_dept\""), "应包含select元素");
        assertTrue(html.contains("option value=\"dev\""), "应包含技术部选项");
        assertTrue(html.contains("option value=\"mkt\""), "应包含市场部选项");
        assertTrue(html.contains("option value=\"hr\""), "应包含人事部选项");
        assertTrue(html.contains("radio-group"), "应包含单选按钮组");
        assertTrue(html.contains("checkbox-group"), "应包含复选框组");
        assertTrue(renderer.getErrors().isEmpty(), "不应有渲染错误");
    }

    @Test
    @DisplayName("日期范围字段渲染测试")
    public void testDateRangeRender() {
        String html = renderer.render(TestDataFactory.dateRangeSchema());
        assertTrue(html.contains("type=\"date\""), "应包含日期选择器");
        assertTrue(html.contains("data-min-date=\"2024-01-01\""), "应有最小日期限制");
        assertTrue(html.contains("data-max-date=\"2024-12-31\""), "应有最大日期限制");
        assertTrue(html.contains("type=\"datetime-local\""), "应包含日期时间选择器");
        assertTrue(html.contains("name=\"field_period_start\""), "应包含日期范围开始字段");
        assertTrue(html.contains("name=\"field_period_end\""), "应包含日期范围结束字段");
        assertTrue(renderer.getErrors().isEmpty(), "不应有渲染错误");
    }

    @Test
    @DisplayName("子表嵌套渲染测试")
    public void testSubTableRender() {
        String html = renderer.render(TestDataFactory.subTableSchema());
        assertTrue(html.contains("data-table=\"field_items\""), "应包含子表容器");
        assertTrue(html.contains("<table>"), "应包含table标签");
        assertTrue(html.contains("物品名称"), "子表列应显示物品名称");
        assertTrue(html.contains("数量"), "子表列应显示数量");
        assertTrue(html.contains("单价"), "子表列应显示单价");
        assertTrue(html.contains("add-row-btn"), "应包含添加行按钮");
        assertTrue(html.contains("data-formula=\"${qty} * ${price}\""), "子表中应包含计算公式");
        assertTrue(renderer.getErrors().isEmpty(), "不应有渲染错误");
    }

    @Test
    @DisplayName("邮箱和手机号正则校验属性测试")
    public void testValidationAttributes() {
        String html = renderer.render(TestDataFactory.simpleTextSchema());
        assertTrue(html.contains("type=\"email\""), "邮箱字段应有email类型");
        assertTrue(html.contains("type=\"tel\""), "手机号字段应有tel类型");
        assertTrue(html.contains("data-pattern=\"^1[3-9]\\\\d{9}$\""), "手机号应有正则校验属性");
        assertTrue(renderer.getErrors().isEmpty(), "不应有渲染错误");
    }

    @Test
    @DisplayName("计算公式C=A+B测试")
    public void testFormulaAddition() {
        Map<String, Object> values = new HashMap<>();
        values.put("field_a", 10);
        values.put("field_b", 20);
        values.put("field_e", 50);
        values.put("field_f", 20);

        Map<String, Object> result = renderer.calculateFormulas(TestDataFactory.formulaSumSchema(), values);

        assertNotNull(result.get("field_c"));
        assertEquals(30.0, result.get("field_c"), "C应该等于A+B=30");
    }

    @Test
    @DisplayName("计算公式D=E*F/100百分比测试")
    public void testFormulaPercentage() {
        Map<String, Object> values = new HashMap<>();
        values.put("field_a", 10);
        values.put("field_b", 20);
        values.put("field_e", 50);
        values.put("field_f", 20);

        Map<String, Object> result = renderer.calculateFormulas(TestDataFactory.formulaSumSchema(), values);

        assertNotNull(result.get("field_d"));
        assertEquals(10.0, result.get("field_d"), "D应该等于E*F/100=10");
    }

    @Test
    @DisplayName("子表金额列求和测试")
    public void testSubTableSumFormula() {
        String jsonRows = "["
                + "{name:'电脑', qty:2, price:5000, total:10000},"
                + "{name:'鼠标', qty:5, price:200, total:1000},"
                + "{name:'键盘', qty:3, price:300, total:900}"
                + "]";
        com.alibaba.fastjson2.JSONArray rows = com.alibaba.fastjson2.JSON.parseArray(jsonRows);

        double sum = renderer.calculateSubTableSum(rows, "total");
        assertEquals(11900.0, sum, 0.001, "子表合计应为11900");
    }

    @Test
    @DisplayName("字段引用提取测试")
    public void testFieldReferenceExtraction() {
        String formula = "${field_a} + ${field_b} * (${field_c} - 100)";
        List<String> refs = renderer.extractFieldReferences(formula);

        assertEquals(3, refs.size(), "应提取3个字段引用");
        assertTrue(refs.contains("field_a"), "应包含field_a");
        assertTrue(refs.contains("field_b"), "应包含field_b");
        assertTrue(refs.contains("field_c"), "应包含field_c");
    }

    @Test
    @DisplayName("异常Schema - 缺少type属性测试")
    public void testMissingTypeSchema() {
        String html = renderer.render(TestDataFactory.missingTypeSchema());
        List<String> errors = renderer.getErrors();

        assertFalse(errors.isEmpty(), "应检测到错误");
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少type属性")), "错误信息应指出缺少type");
        assertTrue(html.contains("render-error"), "应渲染错误提示HTML");
        assertTrue(html.contains("表单渲染错误"), "应有错误标题");
    }

    @Test
    @DisplayName("异常Schema - 循环引用测试")
    public void testCyclicReferenceSchema() {
        renderer.render(TestDataFactory.cyclicFormulaSchema());
        List<String> errors = renderer.getErrors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("循环引用")), "应检测到循环引用错误");
    }

    @Test
    @DisplayName("异常Schema - 无效正则表达式测试")
    public void testInvalidPatternSchema() {
        renderer.render(TestDataFactory.invalidPatternSchema());
        List<String> errors = renderer.getErrors();

        assertTrue(errors.stream().anyMatch(e -> e.contains("正则表达式无效")), "应检测到无效正则错误");
    }

    @Test
    @DisplayName("空Schema处理测试")
    public void testEmptySchema() {
        String html = renderer.render("");
        assertTrue(renderer.getErrors().size() > 0, "空Schema应有错误");
        assertTrue(html.contains("render-error"), "应显示错误页面");
    }

    @Test
    @DisplayName("无效JSON处理测试")
    public void testInvalidJsonSchema() {
        String html = renderer.render("{invalid json");
        assertTrue(renderer.getErrors().size() > 0, "无效JSON应有错误");
        assertTrue(html.contains("render-error"), "应显示错误页面");
    }
}
