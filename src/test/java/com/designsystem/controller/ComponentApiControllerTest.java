package com.designsystem.controller;

import com.designsystem.common.Result;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.service.ComponentService;
import com.designsystem.service.DesignTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@AutoConfigureMockMvc
@DisplayName("Controller层MockMvc测试")
class ComponentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComponentService componentService;

    @MockBean
    private DesignTokenService tokenService;

    private Component testComponent;

    @BeforeEach
    void setUp() {
        testComponent = new Component();
        testComponent.setId(1L);
        testComponent.setName("Button");
        testComponent.setDisplayName("按钮");
        testComponent.setCategory("基础组件");
        testComponent.setFramework("react");
        testComponent.setLatestVersion("1.0.0");
        testComponent.setDescription("通用按钮组件");
    }

    @Nested
    @DisplayName("组件管理API测试")
    class ComponentManagementApiTests {

        @Test
        @WithMockUser(roles = "DEVELOPER")
        @DisplayName("GET /api/components 应返回组件列表")
        void shouldReturnComponentList() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<Component> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
            page.setRecords(List.of(testComponent));
            page.setTotal(1);

            when(componentService.getComponentPage(any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/components")
                            .param("pageNum", "1")
                            .param("pageSize", "10")
                            .param("keyword", "")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].name").value("Button"))
                    .andExpect(jsonPath("$.data.total").value(1));
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        @DisplayName("GET /api/components/{id} 应返回组件详情")
        void shouldReturnComponentDetail() throws Exception {
            when(componentService.getComponentById(1L)).thenReturn(testComponent);

            mockMvc.perform(get("/api/components/1")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("Button"))
                    .andExpect(jsonPath("$.data.displayName").value("按钮"))
                    .andExpect(jsonPath("$.data.latestVersion").value("1.0.0"));
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        @DisplayName("POST /api/components 应创建新组件")
        void shouldCreateNewComponent() throws Exception {
            when(componentService.createComponent(any(Component.class))).thenReturn(testComponent);

            String requestBody = """
                    {
                      "name": "Button",
                      "displayName": "按钮",
                      "category": "基础组件",
                      "framework": "react",
                      "description": "通用按钮组件"
                    }
                    """;

            mockMvc.perform(post("/api/components")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("Button"));
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        @DisplayName("PUT /api/components/{id} 应更新组件")
        void shouldUpdateComponent() throws Exception {
            testComponent.setDisplayName("更新后的按钮");
            when(componentService.updateComponent(any(Component.class))).thenReturn(testComponent);

            String requestBody = """
                    {
                      "id": 1,
                      "name": "Button",
                      "displayName": "更新后的按钮",
                      "category": "基础组件",
                      "framework": "react"
                    }
                    """;

            mockMvc.perform(put("/api/components/1")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.displayName").value("更新后的按钮"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("POST /api/components/{id}/publish 应发布组件")
        void shouldPublishComponent() throws Exception {
            mockMvc.perform(post("/api/components/1/publish")
                            .with(csrf())
                            .param("version", "1.1.0")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("设计令牌API测试")
    class TokenManagementApiTests {

        @Test
        @WithMockUser(roles = "DESIGNER")
        @DisplayName("GET /api/tokens 应返回令牌列表")
        void shouldReturnTokenList() throws Exception {
            DesignToken token = new DesignToken();
            token.setId(1L);
            token.setTokenName("--color-primary");
            token.setBaseValue("#3b82f6");

            com.baomidou.mybatisplus.core.metadata.IPage<DesignToken> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
            page.setRecords(List.of(token));
            page.setTotal(1);

            when(tokenService.getTokenPage(any(), any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/tokens")
                            .param("pageNum", "1")
                            .param("pageSize", "10")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].tokenName").value("--color-primary"));
        }

        @Test
        @WithMockUser(roles = "DESIGNER")
        @DisplayName("GET /api/tokens/{id} 应返回令牌详情")
        void shouldReturnTokenDetail() throws Exception {
            DesignToken token = new DesignToken();
            token.setId(1L);
            token.setTokenName("--color-primary");
            token.setBaseValue("#3b82f6");

            when(tokenService.getTokenById(1L)).thenReturn(token);

            mockMvc.perform(get("/api/tokens/1")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.tokenName").value("--color-primary"))
                    .andExpect(jsonPath("$.data.baseValue").value("#3b82f6"));
        }

        @Test
        @WithMockUser(roles = "DESIGN_LEAD")
        @DisplayName("PUT /api/tokens/{id} 应更新令牌")
        void shouldUpdateToken() throws Exception {
            DesignToken token = new DesignToken();
            token.setId(1L);
            token.setTokenName("--color-primary");
            token.setBaseValue("#2563eb");

            when(tokenService.updateToken(any(DesignToken.class))).thenReturn(token);

            String requestBody = """
                    {
                      "id": 1,
                      "tokenName": "--color-primary",
                      "baseValue": "#2563eb"
                    }
                    """;

            mockMvc.perform(put("/api/tokens/1")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.baseValue").value("#2563eb"));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("GET /api/public/tokens/export/{format} 应导出令牌")
        void shouldExportTokens() throws Exception {
            String cssOutput = ":root {\n  --color-primary: #3b82f6;\n}\n";
            when(tokenService.exportTokens(any(), any(), any())).thenReturn(cssOutput);

            mockMvc.perform(get("/api/public/tokens/export/CSS")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("text/css;charset=UTF-8"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("--color-primary")));
        }

        @Test
        @WithMockUser(roles = "DESIGNER")
        @DisplayName("GET /api/tokens/{id}/impact 应返回影响分析")
        void shouldReturnImpactAnalysis() throws Exception {
            when(tokenService.getTokenImpactAnalysis(1L)).thenReturn(
                    java.util.Map.of(
                            "token", new DesignToken(),
                            "affectedComponents", List.of(),
                            "affectedTokens", List.of(),
                            "changeHistory", List.of()
                    )
            );

            mockMvc.perform(get("/api/tokens/1/impact")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("权限控制测试")
    class AuthorizationTests {

        @Test
        @DisplayName("未登录用户访问API应返回401")
        void shouldRejectUnauthenticatedRequests() throws Exception {
            mockMvc.perform(get("/api/components")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        @DisplayName("无权限用户发布组件应返回403")
        void shouldRejectUnauthorizedComponentPublish() throws Exception {
            mockMvc.perform(post("/api/components/1/publish")
                            .with(csrf())
                            .param("version", "1.1.0")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "DEVELOPER")
        @DisplayName("开发者修改令牌应返回403")
        void shouldRejectDeveloperTokenUpdate() throws Exception {
            String requestBody = """
                    {
                      "id": 1,
                      "tokenName": "--color-primary",
                      "baseValue": "#2563eb"
                    }
                    """;

            mockMvc.perform(put("/api/tokens/1")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden());
        }
    }
}
