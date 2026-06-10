package com.company.dbstudio.update;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("版本管理器测试")
class VersionManagerTest {

    private static VersionManager versionManager;

    @BeforeAll
    static void setup() {
        System.setProperty("dbstudio.version", "1.0.0");
        System.setProperty("dbstudio.implementation-vendor", "Test Vendor");
        System.setProperty("dbstudio.implementation-title", "DBStudio Test");
        System.setProperty("dbstudio.build-timestamp", "2024-01-15T10:30:00");
        System.setProperty("dbstudio.gitHub-repo", "test/dbstudio");

        versionManager = VersionManager.getInstance();
    }

    @Test
    @DisplayName("单例模式验证")
    void testSingleton() {
        VersionManager instance1 = VersionManager.getInstance();
        VersionManager instance2 = VersionManager.getInstance();

        assertSame(instance1, instance2, "Should return the same instance");
    }

    @Test
    @DisplayName("获取当前版本")
    void testGetCurrentVersion() {
        assertNotNull(versionManager.getCurrentVersion());
        assertNotNull(versionManager.getVersionString());
        assertFalse(versionManager.getVersionString().isEmpty());
    }

    @Test
    @DisplayName("获取版本号组件")
    void testGetVersionComponents() {
        assertEquals(1, versionManager.getMajorVersion());
        assertEquals(0, versionManager.getMinorVersion());
        assertEquals(0, versionManager.getPatchVersion());
    }

    @Test
    @DisplayName("获取供应商信息")
    void testGetVendor() {
        assertNotNull(versionManager.getVendor());
        assertFalse(versionManager.getVendor().isEmpty());
    }

    @Test
    @DisplayName("获取应用名称")
    void testGetAppName() {
        assertNotNull(versionManager.getAppName());
        assertFalse(versionManager.getAppName().isEmpty());
    }

    @Test
    @DisplayName("获取构建时间戳")
    void testGetBuildTimestamp() {
        assertNotNull(versionManager.getBuildTimestamp());
    }

    @Test
    @DisplayName("获取GitHub仓库")
    void testGetGitHubRepo() {
        assertNotNull(versionManager.getGitHubRepo());
        assertFalse(versionManager.getGitHubRepo().isEmpty());
        assertTrue(versionManager.getGitHubRepo().contains("/"));
    }

    @Test
    @DisplayName("获取GitHub发布页面URL")
    void testGetGitHubReleasesUrl() {
        String url = versionManager.getGitHubReleasesUrl();
        assertNotNull(url);
        assertTrue(url.startsWith("https://github.com/"));
        assertTrue(url.endsWith("/releases"));
    }

    @Test
    @DisplayName("获取GitHub API URL")
    void testGetGitHubLatestReleaseApiUrl() {
        String url = versionManager.getGitHubLatestReleaseApiUrl();
        assertNotNull(url);
        assertTrue(url.startsWith("https://api.github.com/"));
        assertTrue(url.contains("/releases/latest"));
    }

    @Test
    @DisplayName("获取完整版本字符串")
    void testGetFullVersionString() {
        String fullVersion = versionManager.getFullVersionString();
        assertNotNull(fullVersion);
        assertFalse(fullVersion.isEmpty());
        assertTrue(fullVersion.contains(versionManager.getAppName()));
        assertTrue(fullVersion.contains(versionManager.getVersionString()));
    }

    @Test
    @DisplayName("ToString返回完整版本信息")
    void testToString() {
        assertEquals(versionManager.getFullVersionString(), versionManager.toString());
    }

    @Test
    @DisplayName("是否为快照版本")
    void testIsSnapshot() {
        assertFalse(versionManager.isSnapshot());
    }

    @Test
    @DisplayName("使用AssertJ验证版本信息")
    void testWithAssertJ() {
        assertThat(versionManager.getVersionString())
                .isNotNull()
                .isNotEmpty()
                .containsPattern("\\d+\\.\\d+\\.\\d+");

        assertThat(versionManager.getAppName())
                .isNotNull()
                .startsWith("DB");

        assertThat(versionManager.getGitHubReleasesUrl())
                .isNotNull()
                .startsWith("https://")
                .contains("github");
    }

    @Test
    @DisplayName("不同环境的版本来源")
    void testVersionSources() {
        String version = versionManager.getVersionString();
        assertNotNull(version);

        assertTrue(
            version.equals("1.0.0") || version.matches("\\d+\\.\\d+\\.\\d+.*"),
            "Version should be 1.0.0 or a valid version string"
        );
    }
}
