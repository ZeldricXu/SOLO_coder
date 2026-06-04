package com.flowplatform.renderer;

import com.flowplatform.common.renderer.*;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("渲染器注册表测试")
public class RendererRegistryTest extends BaseUnitTest {

    private RendererRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RendererRegistry(List.of(
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
    }

    @Test
    @DisplayName("所有渲染器已注册正确的类型键")
    public void testAllRenderersRegistered() {
        Set<String> types = registry.getSupportedTypes();

        assertTrue(types.contains("textInput"), "应注册 textInput");
        assertTrue(types.contains("textarea"), "应注册 textarea");
        assertTrue(types.contains("number"), "应注册 number");
        assertTrue(types.contains("amount"), "应注册 amount");
        assertTrue(types.contains("email"), "应注册 email");
        assertTrue(types.contains("phone"), "应注册 phone");
        assertTrue(types.contains("select"), "应注册 select");
        assertTrue(types.contains("radio"), "应注册 radio");
        assertTrue(types.contains("checkbox"), "应注册 checkbox");
        assertTrue(types.contains("date"), "应注册 date");
        assertTrue(types.contains("datetime"), "应注册 datetime");
        assertTrue(types.contains("dateRange"), "应注册 dateRange");
        assertTrue(types.contains("fileUpload"), "应注册 fileUpload");
        assertTrue(types.contains("signature"), "应注册 signature");
        assertTrue(types.contains("subTable"), "应注册 subTable");
        assertTrue(types.contains("formula"), "应注册 formula");
        assertTrue(types.contains("default"), "应注册 default");
    }

    @Test
    @DisplayName("getRenderer 返回正确的渲染器类型")
    public void testGetRendererReturnsCorrectType() {
        assertInstanceOf(TextFieldRenderer.class, registry.getRenderer("textInput"));
        assertInstanceOf(TextareaFieldRenderer.class, registry.getRenderer("textarea"));
        assertInstanceOf(NumberFieldRenderer.class, registry.getRenderer("number"));
        assertInstanceOf(AmountFieldRenderer.class, registry.getRenderer("amount"));
        assertInstanceOf(EmailFieldRenderer.class, registry.getRenderer("email"));
        assertInstanceOf(PhoneFieldRenderer.class, registry.getRenderer("phone"));
        assertInstanceOf(SelectFieldRenderer.class, registry.getRenderer("select"));
        assertInstanceOf(RadioFieldRenderer.class, registry.getRenderer("radio"));
        assertInstanceOf(CheckboxFieldRenderer.class, registry.getRenderer("checkbox"));
        assertInstanceOf(DatePickerRenderer.class, registry.getRenderer("date"));
        assertInstanceOf(DateTimePickerRenderer.class, registry.getRenderer("datetime"));
        assertInstanceOf(DateRangeRenderer.class, registry.getRenderer("dateRange"));
        assertInstanceOf(FileUploadRenderer.class, registry.getRenderer("fileUpload"));
        assertInstanceOf(SignatureRenderer.class, registry.getRenderer("signature"));
        assertInstanceOf(SubFormRenderer.class, registry.getRenderer("subTable"));
        assertInstanceOf(FormulaRenderer.class, registry.getRenderer("formula"));
    }

    @Test
    @DisplayName("getRenderer 未知类型返回 DefaultFieldRenderer")
    public void testGetRendererUnknownTypeReturnsDefault() {
        FieldRenderer renderer = registry.getRenderer("unknown");
        assertNotNull(renderer);
        assertInstanceOf(DefaultFieldRenderer.class, renderer);
    }

    @Test
    @DisplayName("动态注册自定义渲染器")
    public void testRegisterCustomRenderer() {
        FieldRenderer customRenderer = new FieldRenderer() {
            @Override
            public String getType() {
                return "custom";
            }

            @Override
            public String render(com.alibaba.fastjson2.JSONObject field) {
                return "<custom-render></custom-render>";
            }
        };

        registry.register(customRenderer);

        FieldRenderer retrieved = registry.getRenderer("custom");
        assertNotNull(retrieved);
        assertSame(customRenderer, retrieved);
        assertTrue(registry.getSupportedTypes().contains("custom"));
    }
}
