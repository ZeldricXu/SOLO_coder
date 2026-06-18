package com.designsystem.util;

import com.designsystem.common.util.SemverUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Semver版本递增规则测试")
class SemverUtilTest {

    @Nested
    @DisplayName("提交消息类型识别测试")
    class BumpTypeDeterminationTests {

        @Test
        @DisplayName("fix: 类型提交应触发PATCH递增")
        void fixCommitShouldTriggerPatchBump() {
            String commitMessage = "fix: 修复按钮点击无响应问题";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.PATCH, bumpType);
        }

        @Test
        @DisplayName("perf: 性能优化应触发PATCH递增")
        void perfCommitShouldTriggerPatchBump() {
            String commitMessage = "perf: 优化列表渲染性能";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.PATCH, bumpType);
        }

        @Test
        @DisplayName("feat: 新功能应触发MINOR递增")
        void featCommitShouldTriggerMinorBump() {
            String commitMessage = "feat: 添加暗黑模式支持";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.MINOR, bumpType);
        }

        @Test
        @DisplayName("带!后缀的提交应触发MAJOR递增")
        void breakingChangeWithExclamationShouldTriggerMajorBump() {
            String commitMessage = "feat!: 重构组件API，移除旧的props";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.MAJOR, bumpType);
        }

        @Test
        @DisplayName("含BREAKING CHANGE:的提交应触发MAJOR递增")
        void breakingChangeInBodyShouldTriggerMajorBump() {
            String commitMessage = "feat: 添加新的主题系统\n\n" +
                    "BREAKING CHANGE: 主题配置格式完全重构";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.MAJOR, bumpType);
        }

        @Test
        @DisplayName("docs: 文档修改不应触发版本递增")
        void docsCommitShouldNotTriggerBump() {
            String commitMessage = "docs: 更新README文档";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.NONE, bumpType);
        }

        @Test
        @DisplayName("带scope的提交消息应正确识别类型")
        void scopedCommitShouldBeParsedCorrectly() {
            String commitMessage = "feat(button): 添加loading状态";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.MINOR, bumpType);
        }

        @Test
        @DisplayName("带scope和!的破坏性提交应正确识别")
        void scopedBreakingCommitShouldBeParsedCorrectly() {
            String commitMessage = "fix(auth)!: 修改token验证逻辑";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.MAJOR, bumpType);
        }

        @Test
        @DisplayName("空消息应返回NONE")
        void emptyMessageShouldReturnNone() {
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType("");
            assertEquals(SemverUtil.BumpType.NONE, bumpType);
        }

        @Test
        @DisplayName("null消息应返回NONE")
        void nullMessageShouldReturnNone() {
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(null);
            assertEquals(SemverUtil.BumpType.NONE, bumpType);
        }

        @Test
        @DisplayName("不符合Conventional Commits格式的消息应返回NONE")
        void nonConventionalMessageShouldReturnNone() {
            String commitMessage = "修复了一些bug";
            SemverUtil.BumpType bumpType = SemverUtil.determineBumpType(commitMessage);
            assertEquals(SemverUtil.BumpType.NONE, bumpType);
        }
    }

    @Nested
    @DisplayName("版本号递增测试")
    class VersionIncrementTests {

        @Test
        @DisplayName("PATCH递增：1.0.0 -> 1.0.1")
        void patchIncrementShouldWork() {
            String newVersion = SemverUtil.incrementVersion("1.0.0", SemverUtil.BumpType.PATCH);
            assertEquals("1.0.1", newVersion);
        }

        @Test
        @DisplayName("MINOR递增应重置PATCH：1.2.3 -> 1.3.0")
        void minorIncrementShouldResetPatch() {
            String newVersion = SemverUtil.incrementVersion("1.2.3", SemverUtil.BumpType.MINOR);
            assertEquals("1.3.0", newVersion);
        }

        @Test
        @DisplayName("MAJOR递增应重置MINOR和PATCH：1.2.3 -> 2.0.0")
        void majorIncrementShouldResetMinorAndPatch() {
            String newVersion = SemverUtil.incrementVersion("1.2.3", SemverUtil.BumpType.MAJOR);
            assertEquals("2.0.0", newVersion);
        }

        @Test
        @DisplayName("NONE递增应保持版本不变")
        void noneIncrementShouldKeepVersion() {
            String newVersion = SemverUtil.incrementVersion("1.2.3", SemverUtil.BumpType.NONE);
            assertEquals("1.2.3", newVersion);
        }

        @ParameterizedTest
        @CsvSource({
                "1.0.0, fix: bug fix, 1.0.1",
                "1.0.0, feat: new feature, 1.1.0",
                "1.0.0, feat!: breaking change, 2.0.0",
                "1.2.3, fix: patch, 1.2.4",
                "1.2.3, feat: minor, 1.3.0",
                "1.2.3, perf: major change\nBREAKING CHANGE: api changed, 2.0.0"
        })
        @DisplayName("根据提交消息自动递增版本")
        void shouldIncrementVersionBasedOnCommitMessage(String current, String message, String expected) {
            String newVersion = SemverUtil.incrementVersion(current, message);
            assertEquals(expected, newVersion);
        }
    }

    @Nested
    @DisplayName("多提交消息版本递增测试")
    class MultipleCommitsVersionTests {

        @Test
        @DisplayName("多个fix提交应只递增一次PATCH")
        void multipleFixCommitsShouldBumpPatchOnce() {
            List<String> commits = List.of(
                    "fix: bug 1",
                    "fix: bug 2",
                    "fix: bug 3"
            );
            String newVersion = SemverUtil.getNextVersionFromChangelogs("1.0.0", commits);
            assertEquals("1.0.1", newVersion);
        }

        @Test
        @DisplayName("包含feat和fix的提交应递增MINOR")
        void mixedFeatAndFixShouldBumpMinor() {
            List<String> commits = List.of(
                    "fix: bug fix",
                    "feat: new feature",
                    "fix: another bug"
            );
            String newVersion = SemverUtil.getNextVersionFromChangelogs("1.0.0", commits);
            assertEquals("1.1.0", newVersion);
        }

        @Test
        @DisplayName("包含BREAKING CHANGE的提交应递增MAJOR")
        void breakingChangeShouldBumpMajorEvenWithOtherCommits() {
            List<String> commits = List.of(
                    "fix: bug fix",
                    "feat: new feature",
                    "refactor!: breaking change"
            );
            String newVersion = SemverUtil.getNextVersionFromChangelogs("1.2.3", commits);
            assertEquals("2.0.0", newVersion);
        }

        @Test
        @DisplayName("无有效提交应保持版本不变")
        void noValidCommitsShouldKeepVersion() {
            List<String> commits = List.of(
                    "docs: update docs",
                    "style: format code",
                    "chore: update deps"
            );
            String newVersion = SemverUtil.getNextVersionFromChangelogs("1.0.0", commits);
            assertEquals("1.0.0", newVersion);
        }
    }

    @Nested
    @DisplayName("版本号验证和比较测试")
    class VersionValidationTests {

        @ParameterizedTest
        @ValueSource(strings = {"1.0.0", "2.1.3", "0.1.0", "10.20.30"})
        @DisplayName("有效的语义化版本号应验证通过")
        void validVersionsShouldPassValidation(String version) {
            assertTrue(SemverUtil.isValidVersion(version));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.0", "v1.0.0", "1.0.0-beta", "abc", "1.0.0.0"})
        @DisplayName("无效的版本号应验证失败")
        void invalidVersionsShouldFailValidation(String version) {
            assertFalse(SemverUtil.isValidVersion(version));
        }

        @Test
        @DisplayName("版本号比较：大于")
        void versionComparisonGreaterThan() {
            assertTrue(SemverUtil.compareVersions("2.0.0", "1.0.0") > 0);
            assertTrue(SemverUtil.compareVersions("1.1.0", "1.0.0") > 0);
            assertTrue(SemverUtil.compareVersions("1.0.1", "1.0.0") > 0);
        }

        @Test
        @DisplayName("版本号比较：小于")
        void versionComparisonLessThan() {
            assertTrue(SemverUtil.compareVersions("1.0.0", "2.0.0") < 0);
            assertTrue(SemverUtil.compareVersions("1.0.0", "1.1.0") < 0);
            assertTrue(SemverUtil.compareVersions("1.0.0", "1.0.1") < 0);
        }

        @Test
        @DisplayName("版本号比较：等于")
        void versionComparisonEqualTo() {
            assertEquals(0, SemverUtil.compareVersions("1.0.0", "1.0.0"));
            assertEquals(0, SemverUtil.compareVersions("2.3.4", "2.3.4"));
        }
    }

    @Test
    @DisplayName("无效版本号应默认从1.0.0开始")
    void invalidVersionShouldDefaultTo1_0_0() {
        String newVersion = SemverUtil.incrementVersion("invalid", SemverUtil.BumpType.PATCH);
        assertEquals("1.0.1", newVersion);
    }
}
