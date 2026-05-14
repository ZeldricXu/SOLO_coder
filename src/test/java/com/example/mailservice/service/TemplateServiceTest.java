package com.example.mailservice.service;

import com.example.mailservice.builder.TestDataBuilder;
import com.example.mailservice.model.MailTemplate;
import com.example.mailservice.repository.MailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TemplateServiceTest {

    @Mock
    private MailTemplateRepository templateRepository;

    @InjectMocks
    private TemplateService templateService;

    @Captor
    private ArgumentCaptor<MailTemplate> templateCaptor;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
    }

    @Test
    @DisplayName("创建模板测试")
    void testCreateTemplate() {
        MailTemplate template = MailTemplate.builder()
                .templateName("欢迎邮件")
                .templateSubject("欢迎加入我们")
                .templateContent("你好 {{name}}，欢迎加入 {{company}}！")
                .variables("name,company")
                .enabled(true)
                .build();

        when(templateRepository.save(any(MailTemplate.class))).thenAnswer(invocation -> {
            MailTemplate saved = invocation.getArgument(0);
            return saved;
        });

        MailTemplate saved = templateService.createTemplate(template);

        verify(templateRepository, times(1)).save(templateCaptor.capture());

        MailTemplate captured = templateCaptor.getValue();
        assertNotNull(captured.getTemplateId());
        assertTrue(captured.getTemplateId().startsWith("tpl_"));
        assertEquals("欢迎邮件", captured.getTemplateName());
        assertTrue(captured.getEnabled());
    }

    @Test
    @DisplayName("模板变量替换测试 - 单个变量")
    void testRenderTemplate_SingleVariable() {
        String templateId = "tpl_single_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject("通知")
                .withContent("你好，{{name}}")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("name", "张三");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertNotNull(result);
        assertEquals(templateId, result.getTemplateId());
        assertEquals("你好，张三", result.getContent());
    }

    @Test
    @DisplayName("模板变量替换测试 - 多个变量")
    void testRenderTemplate_MultipleVariables() {
        String templateId = "tpl_multi_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject("{{subject}}")
                .withContent("尊敬的 {{name}}，您的订单 {{orderId}} 已发货，预计 {{date}} 送达")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("subject", "订单发货通知");
        variables.put("name", "李四");
        variables.put("orderId", "ORD-2026-001");
        variables.put("date", "2026-05-15");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertNotNull(result);
        assertEquals("订单发货通知", result.getSubject());
        assertEquals("尊敬的 李四，您的订单 ORD-2026-001 已发货，预计 2026-05-15 送达", result.getContent());
    }

    @Test
    @DisplayName("模板变量替换测试 - 部分变量缺失")
    void testRenderTemplate_PartialVariables() {
        String templateId = "tpl_partial_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withContent("你好 {{name}}，今天是 {{day}}，天气 {{weather}}")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("name", "王五");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertNotNull(result);
        assertTrue(result.getContent().contains("王五"));
        assertTrue(result.getContent().contains("{{day}}"));
        assertTrue(result.getContent().contains("{{weather}}"));
    }

    @Test
    @DisplayName("模板变量替换测试 - 无变量")
    void testRenderTemplate_NoVariables() {
        String templateId = "tpl_no_var_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject("固定内容")
                .withContent("这是固定的邮件内容")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertNotNull(result);
        assertEquals("固定内容", result.getSubject());
        assertEquals("这是固定的邮件内容", result.getContent());
    }

    @Test
    @DisplayName("渲染不存在的模板 - 返回null")
    void testRenderTemplate_NonExistent() {
        when(templateRepository.findByTemplateId("nonexistent")).thenReturn(Optional.empty());

        TemplateService.RenderedTemplate result = templateService.renderTemplate("nonexistent", new HashMap<>());

        assertNull(result);
    }

    @Test
    @DisplayName("根据模板ID获取模板")
    void testGetTemplateByTemplateId() {
        String templateId = "tpl_get_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withTemplateName("测试模板")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Optional<MailTemplate> result = templateService.getTemplateByTemplateId(templateId);

        assertTrue(result.isPresent());
        assertEquals(templateId, result.get().getTemplateId());
    }

    @Test
    @DisplayName("根据模板名称获取模板")
    void testGetTemplateByName() {
        String templateName = "测试模板名称";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateName(templateName)
                .build();

        when(templateRepository.findByTemplateName(templateName)).thenReturn(Optional.of(template));

        Optional<MailTemplate> result = templateService.getTemplateByName(templateName);

        assertTrue(result.isPresent());
        assertEquals(templateName, result.get().getTemplateName());
    }

    @Test
    @DisplayName("获取所有启用的模板")
    void testGetAllEnabledTemplates() {
        List<MailTemplate> templates = new ArrayList<>();
        templates.add(TestDataBuilder.MailTemplateBuilder.create().withEnabled(true).build());
        templates.add(TestDataBuilder.MailTemplateBuilder.create().withEnabled(true).build());
        templates.add(TestDataBuilder.MailTemplateBuilder.create().withEnabled(false).build());

        List<MailTemplate> enabledOnly = new ArrayList<>();
        for (MailTemplate t : templates) {
            if (t.getEnabled() != null && t.getEnabled()) {
                enabledOnly.add(t);
            }
        }

        when(templateRepository.findByEnabledTrue()).thenReturn(enabledOnly);

        List<MailTemplate> result = templateService.getAllEnabledTemplates();

        assertEquals(2, result.size());
        verify(templateRepository, times(1)).findByEnabledTrue();
    }

    @Test
    @DisplayName("更新模板测试")
    void testUpdateTemplate() {
        String templateId = "tpl_update_001";
        MailTemplate original = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withTemplateName("原始名称")
                .withSubject("原始主题")
                .withContent("原始内容")
                .withEnabled(true)
                .build();

        MailTemplate updateRequest = MailTemplate.builder()
                .templateName("更新后的名称")
                .templateSubject("更新后的主题")
                .templateContent("更新后的内容 {{var}}")
                .variables("var")
                .enabled(true)
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(original));
        when(templateRepository.save(any(MailTemplate.class))).thenReturn(updateRequest);

        MailTemplate updated = templateService.updateTemplate(templateId, updateRequest);

        verify(templateRepository, times(1)).save(templateCaptor.capture());

        MailTemplate saved = templateCaptor.getValue();
        assertEquals("更新后的名称", saved.getTemplateName());
        assertEquals("更新后的主题", saved.getTemplateSubject());
        assertEquals("更新后的内容 {{var}}", saved.getTemplateContent());
    }

    @Test
    @DisplayName("更新不存在的模板 - 返回null")
    void testUpdateNonExistentTemplate() {
        when(templateRepository.findByTemplateId("nonexistent")).thenReturn(Optional.empty());

        MailTemplate updateRequest = MailTemplate.builder().build();
        MailTemplate result = templateService.updateTemplate("nonexistent", updateRequest);

        assertNull(result);
        verify(templateRepository, never()).save(any(MailTemplate.class));
    }

    @Test
    @DisplayName("删除模板测试")
    void testDeleteTemplate() {
        String templateId = "tpl_delete_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));
        doNothing().when(templateRepository).delete(any(MailTemplate.class));

        templateService.deleteTemplate(templateId);

        verify(templateRepository, times(1)).delete(template);
    }

    @Test
    @DisplayName("删除不存在的模板 - 不应报错")
    void testDeleteNonExistentTemplate() {
        when(templateRepository.findByTemplateId("nonexistent")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> {
            templateService.deleteTemplate("nonexistent");
        });
    }

    @Test
    @DisplayName("禁用模板后不再出现在启用列表中")
    void testDisabledTemplate_NotInEnabledList() {
        MailTemplate enabledTemplate = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateName("启用模板")
                .withEnabled(true)
                .build();
        MailTemplate disabledTemplate = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateName("禁用模板")
                .withEnabled(false)
                .build();

        when(templateRepository.findByEnabledTrue()).thenReturn(Collections.singletonList(enabledTemplate));

        List<MailTemplate> enabledTemplates = templateService.getAllEnabledTemplates();

        assertEquals(1, enabledTemplates.size());
        assertEquals("启用模板", enabledTemplates.get(0).getTemplateName());
    }

    @Test
    @DisplayName("模板变量重复出现 - 所有都被替换")
    void testRenderTemplate_RepeatedVariables() {
        String templateId = "tpl_repeat_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject("{{name}} 的通知")
                .withContent("尊敬的 {{name}}，请查看您的订单。{{name}}，如有问题请联系我们。")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("name", "赵六");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        String expectedContent = "尊敬的 赵六，请查看您的订单。赵六，如有问题请联系我们。";
        assertEquals(expectedContent, result.getContent());
    }

    @Test
    @DisplayName("模板变量边界情况 - 空变量不影响")
    void testRenderTemplate_EmptyVariableValue() {
        String templateId = "tpl_empty_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject("空值测试")
                .withContent("你好{{name}}，今天是{{date}}")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("name", "");
        variables.put("date", "2026-05-10");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        String expected = "你好，今天是2026-05-10";
        assertEquals(expected, result.getContent());
    }

    @Test
    @DisplayName("复杂场景 - 完整模板渲染流程")
    void testCompleteRenderScenario() {
        String templateId = "tpl_complete_001";
        String templateSubject = "关于 {{company}} 订单 {{orderNo}} 的确认";
        String templateContent =
                "<html>" +
                "<body>" +
                "<h1>尊敬的 {{name}}，您好！</h1>" +
                "<p>感谢您购买 {{product}}，订单金额：{{amount}} 元</p>" +
                "<p>订单日期：{{orderDate}}</p>" +
                "<p>此致，<br/>{{company}} 团队</p>" +
                "</body>" +
                "</html>";

        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withSubject(templateSubject)
                .withContent(templateContent)
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = TestDataBuilder.createTemplateVariables();
        variables.put("orderNo", "ORD-2026-1001");
        variables.put("product", "企业服务套餐");
        variables.put("amount", "5,000.00");
        variables.put("orderDate", "2026-05-10");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertNotNull(result);
        assertTrue(result.getSubject().contains("ABC公司"));
        assertTrue(result.getSubject().contains("ORD-2026-1001"));
        assertTrue(result.getContent().contains("张三"));
        assertTrue(result.getContent().contains("企业服务套餐"));
        assertTrue(result.getContent().contains("5,000.00"));
        assertTrue(result.getContent().contains("2026-05-10"));
    }

    @Test
    @DisplayName("模板变量大小写敏感测试")
    void testVariableName_CaseSensitive() {
        String templateId = "tpl_case_001";
        MailTemplate template = TestDataBuilder.MailTemplateBuilder.create()
                .withTemplateId(templateId)
                .withContent("{{Name}} vs {{name}} vs {{NAME}}")
                .build();

        when(templateRepository.findByTemplateId(templateId)).thenReturn(Optional.of(template));

        Map<String, String> variables = new HashMap<>();
        variables.put("name", "小写");
        variables.put("Name", "首字母大写");
        variables.put("NAME", "全大写");

        TemplateService.RenderedTemplate result = templateService.renderTemplate(templateId, variables);

        assertEquals("首字母大写 vs 小写 vs 全大写", result.getContent());
    }
}
