package com.taskplatform.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.persistence.entity.PromptVersion;
import com.taskplatform.persistence.mapper.PromptVersionMapper;
import com.taskplatform.test.builder.PromptVersionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Prompt版本服务测试 - 参数校验完备性验证")
class PromptVersionServiceTest {

    @Mock
    private PromptVersionMapper versionMapper;

    private PromptVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new PromptVersionService(versionMapper);
    }

    @Nested
    @DisplayName("创建版本测试 - 参数校验")
    class CreateVersionTests {

        @Test
        @DisplayName("正常创建 - 应成功并返回版本")
        void shouldCreateVersionSuccessfully() {
            String promptKey = "greeting";
            String content = "Hello, {{name}}!";
            String description = "Greeting prompt";
            String createdBy = "admin";
            Map<String, Object> variables = Map.of("name", "String");

            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            PromptVersion result = versionService.createVersion(
                    promptKey, content, description, createdBy, variables);

            assertThat(result).isNotNull();
            assertThat(result.getPromptKey()).isEqualTo(promptKey);
            assertThat(result.getContent()).isEqualTo(content);
            assertThat(result.getDescription()).isEqualTo(description);
            assertThat(result.getCreatedBy()).isEqualTo(createdBy);
            assertThat(result.getVersion()).startsWith("1.");
            assertThat(result.getVariables()).isEqualTo(variables);
            verify(versionMapper, times(1)).insert(any(PromptVersion.class));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("空promptKey - 应抛出400异常")
        void shouldRejectEmptyPromptKey(String promptKey) {
            BusinessException exception = catchThrowableOfType(
                    () -> versionService.createVersion(promptKey, "content", "desc", "user", null),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
            assertThat(exception.getMessage()).contains("promptKey");
            verify(versionMapper, never()).insert(any());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("空content - 应抛出400异常")
        void shouldRejectEmptyContent(String content) {
            BusinessException exception = catchThrowableOfType(
                    () -> versionService.createVersion("valid-key", content, "desc", "user", null),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("content");
        }

        @Test
        @DisplayName("超长promptKey - 应抛出400异常")
        void shouldRejectTooLongPromptKey() {
            String longKey = "a".repeat(101);

            BusinessException exception = catchThrowableOfType(
                    () -> versionService.createVersion(longKey, "content", "desc", "user", null),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("100");
        }

        @Test
        @DisplayName("特殊字符promptKey - 应接受")
        void shouldAcceptValidSpecialCharacters() {
            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            List<String> validKeys = List.of(
                    "user.greeting",
                    "order_confirm",
                    "api-v1-endpoint",
                    "group.sub.key",
                    "KEY_123"
            );

            for (String key : validKeys) {
                assertThatCode(() -> versionService.createVersion(key, "content", "desc", "user", null))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("空变量Map - 应正常创建")
        void shouldAcceptNullVariables() {
            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            PromptVersion result = versionService.createVersion(
                    "test", "content", "desc", "user", null);

            assertThat(result).isNotNull();
            assertThat(result.getVariables()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("变量名包含特殊字符 - 应拒绝")
        void shouldRejectInvalidVariableNames() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("invalid var", "value");
            variables.put("validVar", "value");

            BusinessException exception = catchThrowableOfType(
                    () -> versionService.createVersion("test", "content", "desc", "user", variables),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("版本自动递增 - 同一key的新版本号应为次版本递增")
        void shouldIncrementMinorVersion() {
            PromptVersion existingV100 = PromptVersionBuilder.aPromptVersion()
                    .withPromptKey("greeting")
                    .withVersion("1.2.0")
                    .build();
            when(versionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(existingV100));
            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            PromptVersion newVersion = versionService.createVersion(
                    "greeting", "new content", "update", "admin", null);

            assertThat(newVersion.getVersion()).startsWith("1.3.");
        }

        @Test
        @DisplayName("空createdBy - 应使用默认值")
        void shouldUseDefaultCreatedBy() {
            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            PromptVersion result = versionService.createVersion(
                    "test", "content", "desc", null, null);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedBy()).isEqualTo("system");
        }
    }

    @Nested
    @DisplayName("模板渲染测试 - 参数校验")
    class TemplateRenderingTests {

        @Test
        @DisplayName("正常渲染 - 应正确替换所有变量")
        void shouldRenderTemplateCorrectly() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Hello, {{user}}! Your order #{{orderId}} has been shipped.")
                    .withVariables(Map.of("user", "String", "orderId", "Number"))
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            Map<String, Object> params = Map.of("user", "Alice", "orderId", 12345);
            String result = versionService.render("greeting", null, params);

            assertThat(result).isEqualTo("Hello, Alice! Your order #12345 has been shipped.");
        }

        @Test
        @DisplayName("缺少必填参数 - 应抛出400异常")
        void shouldRejectMissingRequiredParams() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Hello, {{name}}!")
                    .withVariables(Map.of("name", "String"))
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            BusinessException exception = catchThrowableOfType(
                    () -> versionService.render("greeting", null, Map.of()),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getErrorCode()).isEqualTo("MISSING_PARAMS");
            assertThat(exception.getMessage()).contains("name");
        }

        @Test
        @DisplayName("参数类型不匹配 - 应抛出400异常")
        void shouldRejectTypeMismatch() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Count: {{count}}")
                    .withVariables(Map.of("count", "Number"))
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            BusinessException exception = catchThrowableOfType(
                    () -> versionService.render("key", null, Map.of("count", "not-a-number")),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("count");
            assertThat(exception.getMessage()).contains("Number");
        }

        @Test
        @DisplayName("版本不存在 - 应抛出404异常")
        void shouldThrow404WhenVersionNotFound() {
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException exception = catchThrowableOfType(
                    () -> versionService.render("nonexistent", null, Map.of()),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("VERSION_NOT_FOUND");
        }

        @Test
        @DisplayName("无变量模板 - 应直接返回内容")
        void shouldReturnContentWhenNoVariables() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Hello World!")
                    .withVariables(new HashMap<>())
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            String result = versionService.render("key", null, Map.of());

            assertThat(result).isEqualTo("Hello World!");
        }

        @Test
        @DisplayName("空参数 - 当无变量需求时应接受")
        void shouldAcceptNullParamsWhenNoVariables() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Static content")
                    .withVariables(new HashMap<>())
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            String result = versionService.render("key", null, null);

            assertThat(result).isEqualTo("Static content");
        }

        @Test
        @DisplayName("额外参数 - 应被忽略")
        void shouldIgnoreExtraParams() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Hello, {{name}}!")
                    .withVariables(Map.of("name", "String"))
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Alice");
            params.put("extra", "ignored");
            params.put("another", 123);

            String result = versionService.render("key", null, params);

            assertThat(result).isEqualTo("Hello, Alice!");
        }

        @Test
        @DisplayName("指定版本号 - 应使用指定版本")
        void shouldUseSpecifiedVersion() {
            PromptVersion v1 = PromptVersionBuilder.aPromptVersion()
                    .withPromptKey("greeting")
                    .withVersion("1.0.0")
                    .withContent("Hello v1")
                    .withVariables(new HashMap<>())
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(v1);

            String result = versionService.render("greeting", "1.0.0", null);

            assertThat(result).isEqualTo("Hello v1");
            ArgumentCaptor<LambdaQueryWrapper<PromptVersion>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(versionMapper).selectOne(captor.capture());
        }

        @Test
        @DisplayName("变量null值 - 应渲染为空字符串")
        void shouldRenderNullAsEmpty() {
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withContent("Value: {{optional}}")
                    .withVariables(Map.of("optional", "String"))
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

            Map<String, Object> params = new HashMap<>();
            params.put("optional", null);

            String result = versionService.render("key", null, params);

            assertThat(result).isEqualTo("Value: ");
        }
    }

    @Nested
    @DisplayName("版本查询测试")
    class VersionQueryTests {

        @Test
        @DisplayName("查询所有版本 - 应按版本降序排列")
        void shouldReturnVersionsSorted() {
            PromptVersion v100 = PromptVersionBuilder.aPromptVersion().withVersion("1.0.0").build();
            PromptVersion v200 = PromptVersionBuilder.aPromptVersion().withVersion("2.0.0").build();
            PromptVersion v150 = PromptVersionBuilder.aPromptVersion().withVersion("1.5.0").build();

            when(versionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(v100, v200, v150));

            List<PromptVersion> result = versionService.listVersions("key");

            assertThat(result).extracting(PromptVersion::getVersion)
                    .containsExactly("2.0.0", "1.5.0", "1.0.0");
        }

        @Test
        @DisplayName("空key查询 - 应抛出400异常")
        void shouldRejectEmptyKeyForListing() {
            assertThatThrownBy(() -> versionService.listVersions(""))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", 400);
        }

        @Test
        @DisplayName("获取最新版本 - 应返回版本号最大的")
        void shouldReturnLatestVersion() {
            PromptVersion latest = PromptVersionBuilder.aPromptVersion()
                    .withVersion("3.0.0")
                    .build();

            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

            PromptVersion result = versionService.getLatestVersion("key");

            assertThat(result).isNotNull();
            assertThat(result.getVersion()).isEqualTo("3.0.0");
        }
    }

    @Nested
    @DisplayName("版本回滚测试")
    class VersionRollbackTests {

        @Test
        @DisplayName("正常回滚 - 应创建新版本")
        void shouldCreateNewVersionOnRollback() {
            PromptVersion targetVersion = PromptVersionBuilder.aPromptVersion()
                    .withId(1L)
                    .withPromptKey("key")
                    .withVersion("1.0.0")
                    .withContent("old content")
                    .withDescription("old description")
                    .withCreatedBy("admin")
                    .withVariables(Map.of("var", "String"))
                    .build();

            when(versionMapper.selectById(1L)).thenReturn(targetVersion);
            when(versionMapper.insert(any(PromptVersion.class))).thenReturn(1);

            PromptVersion result = versionService.rollbackToVersion(1L, "user");

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEqualTo("old content");
            assertThat(result.getDescription()).contains("rollback");
            assertThat(result.getDescription()).contains("1.0.0");
            assertThat(result.getCreatedBy()).isEqualTo("user");
            verify(versionMapper, times(1)).insert(any(PromptVersion.class));
        }

        @Test
        @DisplayName("回滚不存在的版本 - 应抛出404")
        void shouldThrow404WhenRollbackNonExistent() {
            when(versionMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> versionService.rollbackToVersion(999L, "user"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", 404);
            verify(versionMapper, never()).insert(any());
        }

        @Test
        @DisplayName("回滚null ID - 应抛出400")
        void shouldRejectNullIdForRollback() {
            assertThatThrownBy(() -> versionService.rollbackToVersion(null, "user"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", 400);
        }
    }
}
