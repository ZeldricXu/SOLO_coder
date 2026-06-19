package com.designsystem.service;

import com.designsystem.common.enums.ExportFormat;
import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.DesignTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenCacheService 令牌缓存服务测试")
class TokenCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private DesignTokenMapper tokenMapper;

    @Mock
    private DesignTokenService tokenService;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ValueOperations<String, String> stringValueOperations;

    @InjectMocks
    private TokenCacheService tokenCacheService;

    private List<DesignToken> mockTokens;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        mockTokens = createMockTokens();
        when(tokenMapper.selectList(any())).thenReturn(mockTokens);
    }

    private List<DesignToken> createMockTokens() {
        List<DesignToken> tokens = new ArrayList<>();

        DesignToken baseBlue = new DesignToken();
        baseBlue.setId(1L);
        baseBlue.setTokenName("--color-blue-500");
        baseBlue.setTokenType(TokenType.COLOR);
        baseBlue.setTokenLevel(TokenLevel.BASE);
        baseBlue.setBaseValue("#3B82F6");
        tokens.add(baseBlue);

        DesignToken semanticPrimary = new DesignToken();
        semanticPrimary.setId(2L);
        semanticPrimary.setTokenName("--color-primary");
        semanticPrimary.setTokenType(TokenType.COLOR);
        semanticPrimary.setTokenLevel(TokenLevel.SEMANTIC);
        semanticPrimary.setInheritsFrom("--color-blue-500");
        tokens.add(semanticPrimary);

        DesignToken componentBtnBg = new DesignToken();
        componentBtnBg.setId(3L);
        componentBtnBg.setTokenName("--color-button-bg");
        componentBtnBg.setTokenType(TokenType.COLOR);
        componentBtnBg.setTokenLevel(TokenLevel.COMPONENT);
        componentBtnBg.setInheritsFrom("--color-primary");
        tokens.add(componentBtnBg);

        DesignToken baseSpacing = new DesignToken();
        baseSpacing.setId(4L);
        baseSpacing.setTokenName("--spacing-md");
        baseSpacing.setTokenType(TokenType.SPACING);
        baseSpacing.setTokenLevel(TokenLevel.BASE);
        baseSpacing.setBaseValue("16px");
        tokens.add(baseSpacing);

        return tokens;
    }

    @Nested
    @DisplayName("拓扑排序测试")
    class TopologicalSortTests {

        @Test
        @DisplayName("正常令牌应能正确拓扑排序")
        void shouldTopologicallySortTokens() {
            when(valueOperations.get("ds:token:all:sorted")).thenReturn(null);

            List<String> sorted = tokenCacheService.getTopologicallySortedTokens();

            assertNotNull(sorted);
            assertEquals(4, sorted.size());
            assertTrue(sorted.indexOf("--color-blue-500") < sorted.indexOf("--color-primary"));
            assertTrue(sorted.indexOf("--color-primary") < sorted.indexOf("--color-button-bg"));
        }

        @Test
        @DisplayName("缓存命中时应直接返回，不重新计算")
        void shouldReturnCachedSortedTokens() {
            List<String> cached = Arrays.asList("--color-blue-500", "--color-primary");
            when(valueOperations.get("ds:token:all:sorted")).thenReturn(cached);

            List<String> result = tokenCacheService.getTopologicallySortedTokens();

            assertSame(cached, result);
            verify(tokenMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("缓存失效与重建测试")
    class CacheInvalidationTests {

        @Test
        @DisplayName("令牌变更时应触发相关缓存失效")
        void shouldInvalidateRelatedCacheOnTokenChange() {
            Map<String, DesignToken> tokenMap = new HashMap<>();
            for (DesignToken t : mockTokens) {
                tokenMap.put(t.getTokenName(), t);
            }
            when(valueOperations.get("ds:token:map")).thenReturn(tokenMap);
            when(valueOperations.get("ds:token:affected:--color-blue-500"))
                    .thenReturn(new HashSet<>(Arrays.asList("--color-primary", "--color-button-bg")));

            tokenCacheService.handleTokenChange("--color-blue-500");

            verify(redisTemplate, atLeastOnce()).delete(anySet());
        }

        @Test
        @DisplayName("应能正确计算受影响的令牌集合")
        void shouldCalculateAffectedTokens() {
            Set<String> expected = new HashSet<>(Arrays.asList("--color-primary", "--color-button-bg"));
            when(valueOperations.get("ds:token:affected:--color-blue-500")).thenReturn(null);
            when(tokenMapper.selectByParentId("--color-blue-500")).thenReturn(
                    Collections.singletonList(mockTokens.get(1))
            );
            when(tokenMapper.selectByParentId("--color-primary")).thenReturn(
                    Collections.singletonList(mockTokens.get(2))
            );
            when(tokenMapper.selectByParentId("--color-button-bg")).thenReturn(Collections.emptyList());

            Set<String> affected = tokenCacheService.getAffectedTokens("--color-blue-500");

            assertNotNull(affected);
            assertTrue(affected.contains("--color-primary"));
            assertTrue(affected.contains("--color-button-bg"));
        }
    }

    @Nested
    @DisplayName("导出格式缓存测试")
    class ExportFormatCacheTests {

        @Test
        @DisplayName("CSS格式导出缓存命中")
        void shouldReturnCachedCssExport() {
            String cachedCss = ":root {\n  --color-blue-500: #3B82F6;\n}";
            when(stringValueOperations.get("ds:token:export:CSS:all:all")).thenReturn(cachedCss);

            String result = tokenCacheService.getExportFormat(ExportFormat.CSS, null, null);

            assertEquals(cachedCss, result);
            verify(tokenService, never()).exportTokens(any(), any(), any());
        }

        @Test
        @DisplayName("缓存未命中时应调用tokenService生成")
        void shouldGenerateExportWhenCacheMiss() {
            String expectedCss = ":root {\n  --color-blue-500: #3B82F6;\n}";
            when(stringValueOperations.get("ds:token:export:CSS:all:all")).thenReturn(null);
            when(tokenService.exportTokens(ExportFormat.CSS, null, null)).thenReturn(expectedCss);

            String result = tokenCacheService.getExportFormat(ExportFormat.CSS, null, null);

            assertEquals(expectedCss, result);
            verify(stringValueOperations).set(eq("ds:token:export:CSS:all:all"), eq(expectedCss), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("JS和JSON格式也应支持缓存")
        void shouldSupportJsAndJsonExports() {
            when(stringValueOperations.get(anyString())).thenReturn(null);
            String jsContent = "export const designTokens = {\n  COLOR_BLUE_500: '#3B82F6',\n};";
            String jsonContent = "{\"tokens\": [{\"name\": \"--color-blue-500\", \"value\": \"#3B82F6\"}]}";
            when(tokenService.exportTokens(eq(ExportFormat.JS), any(), any())).thenReturn(jsContent);
            when(tokenService.exportTokens(eq(ExportFormat.JSON), any(), any())).thenReturn(jsonContent);

            String jsResult = tokenCacheService.getExportFormat(ExportFormat.JS, "COLOR", null);
            String jsonResult = tokenCacheService.getExportFormat(ExportFormat.JSON, "COLOR", null);

            assertEquals(jsContent, jsResult);
            assertEquals(jsonContent, jsonResult);
        }
    }

    @Nested
    @DisplayName("令牌值解析缓存测试")
    class ResolvedValueCacheTests {

        @Test
        @DisplayName("应能从缓存获取已解析的令牌值")
        void shouldGetResolvedValueFromCache() {
            Map<String, String> resolved = new HashMap<>();
            resolved.put("--color-primary", "#3B82F6");
            resolved.put("--color-button-bg", "#3B82F6");
            when(valueOperations.get("ds:token:resolved:values")).thenReturn(resolved);

            String result = tokenCacheService.getResolvedTokenValue("--color-primary");

            assertEquals("#3B82F6", result);
        }

        @Test
        @DisplayName("缓存未命中时应构建解析值缓存")
        void shouldBuildResolvedValuesCacheOnMiss() {
            when(valueOperations.get("ds:token:resolved:values")).thenReturn(null);

            Map<String, String> result = tokenCacheService.getAllResolvedValues();

            assertNotNull(result);
            verify(valueOperations).set(eq("ds:token:resolved:values"), anyMap(), anyLong(), any(TimeUnit.class));
        }
    }

    @Nested
    @DisplayName("继承链缓存测试")
    class InheritanceChainCacheTests {

        @Test
        @DisplayName("应能正确获取并缓存继承链")
        void shouldGetAndCacheInheritanceChain() {
            Set<String> expectedChain = new HashSet<>(
                    Arrays.asList("--color-button-bg", "--color-primary", "--color-blue-500")
            );
            when(valueOperations.get("ds:token:inheritance:chain:--color-button-bg")).thenReturn(null);
            when(tokenMapper.selectByName("--color-button-bg")).thenReturn(mockTokens.get(2));
            when(tokenMapper.selectByName("--color-primary")).thenReturn(mockTokens.get(1));
            when(tokenMapper.selectByName("--color-blue-500")).thenReturn(mockTokens.get(0));

            Set<String> chain = tokenCacheService.getInheritanceChain("--color-button-bg");

            assertNotNull(chain);
            verify(valueOperations).set(
                    eq("ds:token:inheritance:chain:--color-button-bg"),
                    anySet(),
                    anyLong(),
                    any(TimeUnit.class)
            );
        }
    }

    @Nested
    @DisplayName("全量缓存重建测试")
    class FullCacheRebuildTests {

        @Test
        @DisplayName("rebuildAllCaches应清除并重建所有缓存")
        void shouldRebuildAllCaches() {
            when(valueOperations.get("ds:token:map")).thenReturn(
                    mockTokens.stream().collect(HashMap::new, (m, t) -> m.put(t.getTokenName(), t), HashMap::putAll)
            );
            when(tokenService.exportTokens(any(), any(), any())).thenReturn("mock content");

            tokenCacheService.rebuildAllCaches();

            verify(redisTemplate, atLeastOnce()).delete(anySet());
            verify(valueOperations).set(eq("ds:token:resolved:values"), any(), anyLong(), any(TimeUnit.class));
            verify(valueOperations).set(eq("ds:token:all:sorted"), any(), anyLong(), any(TimeUnit.class));
        }
    }

    @Nested
    @DisplayName("令牌Map缓存测试")
    class TokenMapCacheTests {

        @Test
        @DisplayName("应缓存所有令牌的Map")
        void shouldCacheTokenMap() {
            when(valueOperations.get("ds:token:map")).thenReturn(null);

            Map<String, DesignToken> result = tokenCacheService.getTokenMap();

            assertNotNull(result);
            assertEquals(4, result.size());
            assertTrue(result.containsKey("--color-blue-500"));
            verify(valueOperations).set(eq("ds:token:map"), anyMap(), anyLong(), any(TimeUnit.class));
        }
    }
}
