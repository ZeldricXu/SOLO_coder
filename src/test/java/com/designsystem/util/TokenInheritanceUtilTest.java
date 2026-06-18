package com.designsystem.util;

import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.common.util.TokenInheritanceUtil;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.DesignTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("设计令牌继承机制测试")
@ExtendWith(MockitoExtension.class)
class TokenInheritanceUtilTest {

    @Mock
    private DesignTokenMapper tokenMapper;

    private TokenInheritanceUtil inheritanceUtil;

    private DesignToken baseBlue500;
    private DesignToken semanticPrimary;
    private DesignToken semanticDanger;
    private DesignToken componentButtonBg;
    private DesignToken componentAlertBg;

    @BeforeEach
    void setUp() {
        inheritanceUtil = new TokenInheritanceUtil(tokenMapper);

        baseBlue500 = createToken("--base-blue-500", "#3b82f6", TokenType.COLOR, TokenLevel.BASE, null);
        semanticPrimary = createToken("--color-primary", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--base-blue-500");
        semanticDanger = createToken("--color-danger", "#ef4444", TokenType.COLOR, TokenLevel.SEMANTIC, null);
        componentButtonBg = createToken("--button-bg-color", null, TokenType.COLOR, TokenLevel.COMPONENT, "--color-primary");
        componentAlertBg = createToken("--alert-bg-color", null, TokenType.COLOR, TokenLevel.COMPONENT, "--color-danger");
    }

    private DesignToken createToken(String name, String value, TokenType type, TokenLevel level, String inheritsFrom) {
        DesignToken token = new DesignToken();
        token.setTokenName(name);
        token.setBaseValue(value);
        token.setTokenType(type);
        token.setTokenLevel(level);
        token.setInheritsFrom(inheritsFrom);
        return token;
    }

    @Nested
    @DisplayName("令牌值解析测试")
    class TokenValueResolutionTests {

        @Test
        @DisplayName("基础令牌应直接返回自身值")
        void baseTokenShouldReturnValueDirectly() {
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            String resolved = inheritanceUtil.resolveTokenValue("--base-blue-500");
            assertEquals("#3b82f6", resolved);
        }

        @Test
        @DisplayName("语义化令牌应继承基础令牌值：tokenB={tokenA}")
        void semanticTokenShouldInheritBaseTokenValue() {
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            String resolved = inheritanceUtil.resolveTokenValue("--color-primary");
            assertEquals("#3b82f6", resolved);
        }

        @Test
        @DisplayName("组件级令牌应继承语义化令牌值")
        void componentTokenShouldInheritSemanticTokenValue() {
            when(tokenMapper.selectByName("--button-bg-color")).thenReturn(componentButtonBg);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            String resolved = inheritanceUtil.resolveTokenValue("--button-bg-color");
            assertEquals("#3b82f6", resolved);
        }

        @Test
        @DisplayName("修改父级令牌后子级令牌应正确更新")
        void modifyingParentShouldUpdateChildValue() {
            DesignToken updatedBaseBlue = createToken("--base-blue-500", "#2563eb", TokenType.COLOR, TokenLevel.BASE, null);
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(updatedBaseBlue);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--button-bg-color")).thenReturn(componentButtonBg);

            String buttonBgValue = inheritanceUtil.resolveTokenValue("--button-bg-color");
            assertEquals("#2563eb", buttonBgValue);
        }

        @Test
        @DisplayName("有自身值的令牌不应继承父级值")
        void tokenWithOwnValueShouldNotInherit() {
            when(tokenMapper.selectByName("--color-danger")).thenReturn(semanticDanger);

            String resolved = inheritanceUtil.resolveTokenValue("--color-danger");
            assertEquals("#ef4444", resolved);
        }

        @Test
        @DisplayName("修改基础色后三层令牌链应全部更新")
        void modifyingBaseShouldUpdateEntireChain() {
            DesignToken newBase = createToken("--base-blue-500", "#1d4ed8", TokenType.COLOR, TokenLevel.BASE, null);

            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(newBase);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--button-bg-color")).thenReturn(componentButtonBg);

            assertEquals("#1d4ed8", inheritanceUtil.resolveTokenValue("--base-blue-500"));
            assertEquals("#1d4ed8", inheritanceUtil.resolveTokenValue("--color-primary"));
            assertEquals("#1d4ed8", inheritanceUtil.resolveTokenValue("--button-bg-color"));
        }
    }

    @Nested
    @DisplayName("循环引用检测测试")
    class CircularReferenceDetectionTests {

        @Test
        @DisplayName("检测A→B→C→A的循环引用")
        void shouldDetectThreeNodeCycle() {
            DesignToken tokenA = createToken("--token-a", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-b");
            DesignToken tokenB = createToken("--token-b", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-c");
            DesignToken tokenC = createToken("--token-c", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-a");

            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);
            when(tokenMapper.selectByName("--token-c")).thenReturn(tokenC);

            boolean hasCycle = inheritanceUtil.hasCircularReference("--token-a", "--token-b");
            assertTrue(hasCycle);
        }

        @Test
        @DisplayName("检测A→B→A的直接循环引用")
        void shouldDetectDirectTwoNodeCycle() {
            DesignToken tokenA = createToken("--token-a", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-b");
            DesignToken tokenB = createToken("--token-b", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-a");

            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);

            boolean hasCycle = inheritanceUtil.hasCircularReference("--token-a", "--token-b");
            assertTrue(hasCycle);
        }

        @Test
        @DisplayName("检测自引用（A→A）")
        void shouldDetectSelfReference() {
            DesignToken tokenA = createToken("--token-a", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-a");

            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);

            boolean hasCycle = inheritanceUtil.hasCircularReference("--token-a", "--token-a");
            assertTrue(hasCycle);
        }

        @Test
        @DisplayName("正常的继承链不应误报为循环引用")
        void shouldNotReportFalsePositiveForValidChain() {
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);

            boolean hasCycle = inheritanceUtil.hasCircularReference("--color-primary", "--base-blue-500");
            assertFalse(hasCycle);
        }

        @Test
        @DisplayName("空的继承目标不应有循环引用")
        void nullInheritsFromShouldNotHaveCycle() {
            boolean hasCycle = inheritanceUtil.hasCircularReference("--color-primary", null);
            assertFalse(hasCycle);
        }

        @Test
        @DisplayName("解析循环引用时应抛出异常")
        void resolvingCyclicTokenShouldThrowException() {
            DesignToken tokenA = createToken("--token-a", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-b");
            DesignToken tokenB = createToken("--token-b", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-a");

            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);

            assertThrows(IllegalStateException.class, () -> {
                inheritanceUtil.resolveTokenValue("--token-a");
            });
        }

        @Test
        @DisplayName("检测所有令牌中的循环引用")
        void shouldDetectAllCircularReferences() {
            DesignToken tokenA = createToken("--token-a", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-b");
            DesignToken tokenB = createToken("--token-b", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--token-a");
            DesignToken tokenC = createToken("--token-c", "#ffffff", TokenType.COLOR, TokenLevel.BASE, null);

            when(tokenMapper.selectList(null)).thenReturn(List.of(tokenA, tokenB, tokenC));
            when(tokenMapper.selectByName("--token-a")).thenReturn(tokenA);
            when(tokenMapper.selectByName("--token-b")).thenReturn(tokenB);

            List<String> cycles = inheritanceUtil.detectAllCircularReferences();
            assertEquals(1, cycles.size());
            assertTrue(cycles.get(0).contains("--token-a") && cycles.get(0).contains("--token-b"));
        }
    }

    @Nested
    @DisplayName("继承链分析测试")
    class InheritanceChainTests {

        @Test
        @DisplayName("获取完整的继承链")
        void shouldGetCompleteInheritanceChain() {
            when(tokenMapper.selectByName("--button-bg-color")).thenReturn(componentButtonBg);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            var chain = inheritanceUtil.getInheritanceChain("--button-bg-color");

            assertEquals(3, chain.size());
            assertTrue(chain.contains("--button-bg-color"));
            assertTrue(chain.contains("--color-primary"));
            assertTrue(chain.contains("--base-blue-500"));
        }

        @Test
        @DisplayName("修改基础令牌后应获取受影响的所有令牌")
        void shouldGetAllAffectedTokens() {
            DesignToken anotherSemantic = createToken("--color-link", null, TokenType.COLOR, TokenLevel.SEMANTIC, "--base-blue-500");
            DesignToken anotherComponent = createToken("--link-color", null, TokenType.COLOR, TokenLevel.COMPONENT, "--color-link");

            when(tokenMapper.selectList(null)).thenReturn(List.of(
                    baseBlue500, semanticPrimary, componentButtonBg, anotherSemantic, anotherComponent
            ));
            when(tokenMapper.selectByName("--color-primary")).thenReturn(semanticPrimary);
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);
            when(tokenMapper.selectByName("--button-bg-color")).thenReturn(componentButtonBg);
            when(tokenMapper.selectByName("--color-link")).thenReturn(anotherSemantic);
            when(tokenMapper.selectByName("--link-color")).thenReturn(anotherComponent);

            var affected = inheritanceUtil.getAffectedTokens("--base-blue-500");

            assertEquals(4, affected.size());
            assertTrue(affected.contains("--color-primary"));
            assertTrue(affected.contains("--button-bg-color"));
            assertTrue(affected.contains("--color-link"));
            assertTrue(affected.contains("--link-color"));
        }
    }

    @Nested
    @DisplayName("缓存机制测试")
    class CacheTests {

        @Test
        @DisplayName("相同令牌多次解析应只查询一次数据库")
        void cachedTokenShouldNotQueryDbMultipleTimes() {
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            inheritanceUtil.resolveTokenValue("--base-blue-500");
            inheritanceUtil.resolveTokenValue("--base-blue-500");
            inheritanceUtil.resolveTokenValue("--base-blue-500");

            verify(tokenMapper, times(1)).selectByName("--base-blue-500");
        }

        @Test
        @DisplayName("清除缓存后应重新查询")
        void clearingCacheShouldResetQueries() {
            when(tokenMapper.selectByName("--base-blue-500")).thenReturn(baseBlue500);

            inheritanceUtil.resolveTokenValue("--base-blue-500");
            inheritanceUtil.clearCache();
            inheritanceUtil.resolveTokenValue("--base-blue-500");

            verify(tokenMapper, times(2)).selectByName("--base-blue-500");
        }
    }
}
