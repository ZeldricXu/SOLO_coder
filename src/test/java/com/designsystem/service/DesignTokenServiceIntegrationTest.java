package com.designsystem.service;

import com.designsystem.DesignSystemApplication;
import com.designsystem.common.PageQuery;
import com.designsystem.common.enums.ComponentFramework;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.DesignTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = DesignSystemApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("Service层集成测试")
class DesignTokenServiceIntegrationTest {

    @Autowired
    private DesignTokenService tokenService;

    @Autowired
    private ComponentService componentService;

    @MockitoBean
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Autowired
    private DesignTokenMapper tokenMapper;

    @Autowired
    private ComponentMapper componentMapper;

    @BeforeEach
    void setUp() {
        tokenService.init();
    }

    @Nested
    @DisplayName("设计令牌CRUD集成测试")
    class TokenCrudIntegrationTests {

        @Test
        @DisplayName("创建令牌并查询")
        void shouldCreateAndRetrieveToken() {
            DesignToken token = new DesignToken();
            token.setTokenName("--test-color");
            token.setDisplayName("测试颜色");
            token.setTokenType(TokenType.COLOR);
            token.setTokenLevel(TokenLevel.BASE);
            token.setBaseValue("#ff0000");
            token.setCategory("测试");

            DesignToken created = tokenService.createToken(token);
            assertNotNull(created.getId());

            DesignToken found = tokenService.getTokenById(created.getId());
            assertNotNull(found);
            assertEquals("--test-color", found.getTokenName());
            assertEquals("#ff0000", found.getBaseValue());
        }

        @Test
        @DisplayName("令牌值继承测试")
        void shouldInheritTokenValue() {
            DesignToken baseToken = new DesignToken();
            baseToken.setTokenName("--base-blue");
            baseToken.setTokenType(TokenType.COLOR);
            baseToken.setTokenLevel(TokenLevel.BASE);
            baseToken.setBaseValue("#3b82f6");
            tokenService.createToken(baseToken);

            DesignToken semanticToken = new DesignToken();
            semanticToken.setTokenName("--color-primary");
            semanticToken.setTokenType(TokenType.COLOR);
            semanticToken.setTokenLevel(TokenLevel.SEMANTIC);
            semanticToken.setInheritsFrom("--base-blue");
            tokenService.createToken(semanticToken);

            String resolved = tokenService.resolveTokenValue("--color-primary");
            assertEquals("#3b82f6", resolved);
        }

        @Test
        @DisplayName("令牌导出为CSS格式")
        void shouldExportTokensToCss() {
            DesignToken token1 = new DesignToken();
            token1.setTokenName("--color-primary");
            token1.setTokenType(TokenType.COLOR);
            token1.setTokenLevel(TokenLevel.BASE);
            token1.setBaseValue("#3b82f6");
            tokenService.createToken(token1);

            DesignToken token2 = new DesignToken();
            token2.setTokenName("--spacing-md");
            token2.setTokenType(TokenType.SPACING);
            token2.setTokenLevel(TokenLevel.BASE);
            token2.setBaseValue("16px");
            tokenService.createToken(token2);

            String css = tokenService.exportTokens(com.designsystem.common.enums.ExportFormat.CSS, null, null);

            assertNotNull(css);
            assertTrue(css.contains("--color-primary: #3b82f6"));
            assertTrue(css.contains("--spacing-md: 16px"));
            assertTrue(css.startsWith(":root {"));
        }

        @Test
        @DisplayName("令牌导出为JSON格式")
        void shouldExportTokensToJson() {
            DesignToken token = new DesignToken();
            token.setTokenName("--color-success");
            token.setTokenType(TokenType.COLOR);
            token.setTokenLevel(TokenLevel.BASE);
            token.setBaseValue("#10b981");
            tokenService.createToken(token);

            String json = tokenService.exportTokens(com.designsystem.common.enums.ExportFormat.JSON, null, null);

            assertNotNull(json);
            assertTrue(json.contains("\"name\": \"--color-success\""));
            assertTrue(json.contains("\"value\": \"#10b981\""));
        }

        @Test
        @DisplayName("令牌导出为JS模块格式")
        void shouldExportTokensToJs() {
            DesignToken token = new DesignToken();
            token.setTokenName("--color-danger");
            token.setTokenType(TokenType.COLOR);
            token.setTokenLevel(TokenLevel.BASE);
            token.setBaseValue("#ef4444");
            tokenService.createToken(token);

            String js = tokenService.exportTokens(com.designsystem.common.enums.ExportFormat.JS, null, null);

            assertNotNull(js);
            assertTrue(js.contains("export const designTokens"));
            assertTrue(js.contains("COLOR_DANGER: '#ef4444'"));
        }

        @Test
        @DisplayName("修改基础色后影响分析应列出受影响的组件")
        void shouldReturnAffectedComponentsAfterBaseColorChange() {
            DesignToken baseColor = new DesignToken();
            baseColor.setTokenName("--base-primary");
            baseColor.setTokenType(TokenType.COLOR);
            baseColor.setTokenLevel(TokenLevel.BASE);
            baseColor.setBaseValue("#3b82f6");
            DesignToken savedToken = tokenService.createToken(baseColor);

            Component component = new Component();
            component.setName("TestButton");
            component.setDisplayName("测试按钮");
            component.setCategory("基础组件");
            component.setFramework(ComponentFramework.REACT.getCode());
            component.setDescription("测试按钮组件");
            component.setMaintainerId(1L);
            componentService.createComponent(component);

            var impact = tokenService.getTokenImpactAnalysis(savedToken.getId());
            assertNotNull(impact);
            assertNotNull(impact.get("token"));
        }
    }

    @Nested
    @DisplayName("组件管理集成测试")
    class ComponentManagementIntegrationTests {

        @Test
        @DisplayName("创建组件并分页查询")
        void shouldCreateAndPageComponents() {
            for (int i = 0; i < 5; i++) {
                Component component = new Component();
                component.setName("Component" + i);
                component.setDisplayName("组件" + i);
                component.setCategory("测试分类");
                component.setFramework("react");
                component.setDescription("测试组件" + i);
                component.setMaintainerId(1L);
                componentService.createComponent(component);
            }

            PageQuery query = new PageQuery();
            query.setPageNum(1);
            query.setPageSize(10);
            query.setKeyword("");

            var page = componentService.getComponentPage(query, null, null);
            assertNotNull(page);
            assertTrue(page.getTotal() >= 5);
        }

        @Test
        @DisplayName("按框架筛选组件")
        void shouldFilterComponentsByFramework() {
            Component reactComponent = new Component();
            reactComponent.setName("ReactButton");
            reactComponent.setDisplayName("React按钮");
            reactComponent.setCategory("按钮");
            reactComponent.setFramework("react");
            reactComponent.setDescription("React按钮组件");
            reactComponent.setMaintainerId(1L);
            componentService.createComponent(reactComponent);

            Component vueComponent = new Component();
            vueComponent.setName("VueButton");
            vueComponent.setDisplayName("Vue按钮");
            vueComponent.setCategory("按钮");
            vueComponent.setFramework("vue");
            vueComponent.setDescription("Vue按钮组件");
            vueComponent.setMaintainerId(1L);
            componentService.createComponent(vueComponent);

            PageQuery query = new PageQuery();
            query.setPageNum(1);
            query.setPageSize(10);
            query.setKeyword("");

            var reactPage = componentService.getComponentPage(query, null, "react");
            var vuePage = componentService.getComponentPage(query, null, "vue");

            assertTrue(reactPage.getRecords().stream()
                    .allMatch(c -> "react".equals(c.getFramework()));
            assertTrue(vuePage.getRecords().stream()
                    .allMatch(c -> "vue".equals(c.getFramework()));
        }

        @Test
        @DisplayName("Marketplace分页查询")
        void shouldReturnMarketplacePage() {
            Component component = new Component();
            component.setName("MarketButton");
            component.setDisplayName("市场按钮");
            component.setCategory("通用");
            component.setFramework("react");
            component.setDescription("市场测试组件");
            component.setMaintainerId(1L);
            component.setPublished(1);
            componentService.createComponent(component);

            PageQuery query = new PageQuery();
            query.setPageNum(1);
            query.setPageSize(10);

            var page = componentService.getMarketplacePage(query, null, null, null);
            assertNotNull(page);
            assertTrue(page.getTotal() >= 1);
        }
    }
}
