package com.designsystem.controller;

import com.designsystem.common.enums.ExportFormat;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.config.TestConfig;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.ComponentMapper;
import com.designsystem.mapper.DesignTokenMapper;
import com.designsystem.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("API兼容性测试 - 重构后所有接口保持兼容")
class ApiCompatibilityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComponentService componentService;

    @MockBean
    private DesignTokenService tokenService;

    @MockBean
    private DocumentationService docService;

    @MockBean
    private TokenCacheService tokenCacheService;

    @MockBean
    private IncrementalDocService incrementalDocService;

    @MockBean
    private ComponentMapper componentMapper;

    @MockBean
    private DesignTokenMapper tokenMapper;

    private Component mockComponent;
    private DesignToken mockToken;

    @BeforeEach
    void setUp() {
        mockComponent = new Component();
        mockComponent.setId(1L);
        mockComponent.setName("button");
        mockComponent.setDisplayName("按钮组件");
        mockComponent.setCategory("基础组件");
        mockComponent.setLatestVersion("v1.0.0");
        mockComponent.setPublished(1);

        mockToken = new DesignToken();
        mockToken.setId(1L);
        mockToken.setTokenName("--color-primary");
        mockToken.setTokenType(TokenType.COLOR);
        mockToken.setTokenLevel(TokenLevel.SEMANTIC);
        mockToken.setBaseValue("#6366f1");
    }

    @Nested
    @DisplayName("Component API 兼容性测试")
    class ComponentApiTests {

        @Test
        @DisplayName("GET /api/components 应返回分页数据")
        @WithMockUser(roles = "USER")
        void shouldReturnComponentList() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<Component> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(Collections.singletonList(mockComponent));
            page.setTotal(1);
            page.setCurrent(1);
            page.setSize(10);
            page.setPages(1);

            when(componentService.getComponentPage(any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/components?pageNum=1&pageSize=10")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records", hasSize(1)))
                    .andExpect(jsonPath("$.data.records[0].name").value("button"))
                    .andExpect(jsonPath("$.data.total").value(1));
        }

        @Test
        @DisplayName("GET /api/components/{id} 应返回单个组件")
        @WithMockUser(roles = "USER")
        void shouldReturnComponentById() throws Exception {
            when(componentService.getComponentById(1L)).thenReturn(mockComponent);

            mockMvc.perform(get("/api/components/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.name").value("button"));
        }

        @Test
        @DisplayName("POST /api/components 应创建组件（兼容旧接口）")
        @WithMockUser(roles = "ADMIN")
        void shouldCreateComponent() throws Exception {
            when(componentService.createComponent(any())).thenReturn(mockComponent);

            String json = "{\"name\":\"button\",\"displayName\":\"按钮组件\",\"category\":\"基础组件\"}";

            mockMvc.perform(post("/api/components")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("PUT /api/components/{id} 应更新组件（兼容旧接口）")
        @WithMockUser(roles = "ADMIN")
        void shouldUpdateComponent() throws Exception {
            when(componentService.updateComponent(any())).thenReturn(mockComponent);

            String json = "{\"name\":\"button\",\"displayName\":\"按钮组件-更新\"}";

            mockMvc.perform(put("/api/components/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("DELETE /api/components/{id} - 新增的删除接口应可用")
        @WithMockUser(roles = "ADMIN")
        void shouldDeleteComponent() throws Exception {
            mockMvc.perform(delete("/api/components/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("Token API 兼容性测试")
    class TokenApiTests {

        @Test
        @DisplayName("GET /api/tokens 应返回令牌分页列表")
        @WithMockUser(roles = "USER")
        void shouldReturnTokenList() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<DesignToken> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(Collections.singletonList(mockToken));
            page.setTotal(1);
            page.setCurrent(1);
            page.setSize(10);
            page.setPages(1);

            when(tokenService.getTokenPage(any(), any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/tokens?pageNum=1&pageSize=10")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records", hasSize(1)))
                    .andExpect(jsonPath("$.data.records[0].tokenName").value("--color-primary"));
        }

        @Test
        @DisplayName("GET /api/tokens/{id} 应返回单个令牌")
        @WithMockUser(roles = "USER")
        void shouldReturnTokenById() throws Exception {
            when(tokenService.getTokenById(1L)).thenReturn(mockToken);

            mockMvc.perform(get("/api/tokens/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.tokenName").value("--color-primary"));
        }

        @Test
        @DisplayName("GET /api/tokens/tree 应返回令牌树")
        @WithMockUser(roles = "USER")
        void shouldReturnTokenTree() throws Exception {
            List<DesignToken> tree = Collections.singletonList(mockToken);
            when(tokenService.getTokenTree()).thenReturn(tree);

            mockMvc.perform(get("/api/tokens/tree")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(1)));
        }

        @Test
        @DisplayName("POST /api/tokens 应创建令牌")
        @WithMockUser(roles = "DESIGNER")
        void shouldCreateToken() throws Exception {
            when(tokenService.createToken(any())).thenReturn(mockToken);

            String json = "{\"tokenName\":\"--color-primary\",\"baseValue\":\"#6366f1\"}";

            mockMvc.perform(post("/api/tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("PUT /api/tokens/{id} 应更新令牌")
        @WithMockUser(roles = "DESIGNER")
        void shouldUpdateToken() throws Exception {
            when(tokenService.updateToken(any())).thenReturn(mockToken);

            String json = "{\"tokenName\":\"--color-primary\",\"baseValue\":\"#818cf8\"}";

            mockMvc.perform(put("/api/tokens/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /api/tokens/export 应支持所有格式导出")
        @WithMockUser(roles = "USER")
        void shouldSupportAllExportFormats() throws Exception {
            String cssContent = ":root { --color-primary: #6366f1; }";
            when(tokenService.exportTokens(eq(ExportFormat.CSS), any(), any())).thenReturn(cssContent);
            when(tokenService.exportTokens(eq(ExportFormat.JS), any(), any())).thenReturn("export const tokens = {}");
            when(tokenService.exportTokens(eq(ExportFormat.JSON), any(), any())).thenReturn("{\"tokens\": []}");

            mockMvc.perform(get("/api/tokens/export?format=CSS")
                            .accept(MediaType.ALL))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString("design-tokens.css")));
        }

        @Test
        @DisplayName("POST /api/tokens/cache/rebuild - 新增缓存重建接口应可用")
        @WithMockUser(roles = "DESIGNER")
        void shouldSupportCacheRebuild() throws Exception {
            mockMvc.perform(post("/api/tokens/cache/rebuild")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /api/tokens/cache/status - 新增缓存状态接口应可用")
        @WithMockUser(roles = "USER")
        void shouldSupportCacheStatus() throws Exception {
            when(tokenService.getAllResolvedTokenValues()).thenReturn(new HashMap<>());

            mockMvc.perform(get("/api/tokens/cache/status")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.enabled").value(true));
        }

        @Test
        @DisplayName("GET /api/tokens/{id}/impact 应返回影响分析")
        @WithMockUser(roles = "USER")
        void shouldReturnImpactAnalysis() throws Exception {
            Map<String, Object> impact = new HashMap<>();
            impact.put("affectedComponents", Collections.emptyList());
            impact.put("affectedTokens", Collections.emptyList());

            when(tokenService.getTokenImpactAnalysis(1L)).thenReturn(impact);

            mockMvc.perform(get("/api/tokens/1/impact")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.affectedComponents").exists());
        }
    }

    @Nested
    @DisplayName("Documentation API 兼容性测试")
    class DocumentationApiTests {

        @Test
        @DisplayName("GET /api/docs/search - 新增的文档搜索接口应可用")
        @WithMockUser(roles = "USER")
        void shouldSupportDocSearch() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<com.designsystem.entity.ComponentDoc> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(Collections.emptyList());
            page.setTotal(0);
            page.setCurrent(1);
            page.setPages(1);

            when(docService.searchDocs(any(), any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/api/docs/search?q=Button&page=1&size=10")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /api/docs/version/{versionId} 应返回版本文档")
        @WithMockUser(roles = "USER")
        void shouldReturnDocsByVersion() throws Exception {
            when(docService.getDocsByVersionId(1L)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/docs/version/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("PUT /api/docs/{id} 应更新文档")
        @WithMockUser(roles = "DEVELOPER")
        void shouldUpdateDoc() throws Exception {
            com.designsystem.entity.ComponentDoc doc = new com.designsystem.entity.ComponentDoc();
            doc.setId(1L);
            doc.setTitle("API文档");
            when(docService.updateDoc(any())).thenReturn(doc);

            String json = "{\"title\":\"API文档更新\",\"content\":\"新内容\"}";

            mockMvc.perform(put("/api/docs/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /api/docs/parse/incremental - 新增增量解析接口应可用")
        @WithMockUser(roles = "DEVELOPER")
        void shouldSupportIncrementalParse() throws Exception {
            Map<String, Object> result = new HashMap<>();
            result.put("totalFiles", 2);
            result.put("changedCount", 1);
            result.put("unchangedCount", 1);
            when(incrementalDocService.incrementalParseFiles(any(), any(), any(), any())).thenReturn(result);

            mockMvc.perform(post("/api/docs/parse/incremental")
                            .param("versionId", "1")
                            .param("framework", "react")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalFiles").value(2));
        }

        @Test
        @DisplayName("GET /api/docs/parse/records/{versionId} - 解析记录接口应可用")
        @WithMockUser(roles = "USER")
        void shouldSupportParseRecords() throws Exception {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalFiles", 5);
            stats.put("successCount", 4);
            stats.put("failedCount", 1);

            when(incrementalDocService.getParseStatistics(1L)).thenReturn(stats);

            mockMvc.perform(get("/api/docs/parse/records/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalFiles").value(5));
        }
    }

    @Nested
    @DisplayName("权限控制兼容性测试")
    class PermissionTests {

        @Test
        @DisplayName("未登录用户访问受保护接口应返回401")
        void shouldReturn401ForUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/components")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("普通用户不能删除组件（需要ADMIN或DEVELOPER）")
        @WithMockUser(roles = "USER")
        void shouldDenyDeleteForRegularUser() throws Exception {
            mockMvc.perform(delete("/api/components/1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("普通用户不能重建令牌缓存（需要ADMIN或DESIGNER）")
        @WithMockUser(roles = "USER")
        void shouldDenyCacheRebuildForRegularUser() throws Exception {
            mockMvc.perform(post("/api/tokens/cache/rebuild")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DESIGNER角色可以重建令牌缓存")
        @WithMockUser(roles = "DESIGNER")
        void shouldAllowCacheRebuildForDesigner() throws Exception {
            mockMvc.perform(post("/api/tokens/cache/rebuild")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DEVELOPER角色可以触发增量解析")
        @WithMockUser(roles = "DEVELOPER")
        void shouldAllowIncrementalParseForDeveloper() throws Exception {
            when(incrementalDocService.incrementalParseFiles(any(), any(), any(), any()))
                    .thenReturn(new HashMap<>());

            mockMvc.perform(post("/api/docs/parse/incremental")
                            .param("versionId", "1")
                            .param("framework", "react")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("SSR页面路由兼容性测试")
    class SsrPageRoutingTests {

        @Test
        @DisplayName("SSR页面应能正常访问 - /components")
        @WithMockUser(roles = "USER")
        void shouldAccessComponentsPage() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<Component> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(Collections.emptyList());
            page.setTotal(0);
            page.setCurrent(1);
            page.setPages(1);

            when(componentService.getComponentPage(any(), any(), any())).thenReturn(page);

            mockMvc.perform(get("/components"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("SSR页面应能正常访问 - /tokens")
        @WithMockUser(roles = "USER")
        void shouldAccessTokensPage() throws Exception {
            com.baomidou.mybatisplus.core.metadata.IPage<DesignToken> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
            page.setRecords(Collections.emptyList());
            page.setTotal(0);
            page.setCurrent(1);
            page.setPages(1);

            when(tokenService.getTokenPage(any(), any(), any(), any())).thenReturn(page);
            when(tokenService.getTokenTree()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/tokens"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("新增文档搜索页面应能访问 - /docs/search")
        @WithMockUser(roles = "USER")
        void shouldAccessDocSearchPage() throws Exception {
            mockMvc.perform(get("/docs/search"))
                    .andExpect(status().isOk());
        }
    }
}
