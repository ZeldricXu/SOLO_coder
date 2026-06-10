package com.company.dbstudio.update;

import com.company.dbstudio.update.model.VersionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("版本信息测试")
class VersionInfoTest {

    @Test
    @DisplayName("解析标准版本号")
    void testParseStandardVersion() {
        VersionInfo version = new VersionInfo("1.2.3");
        assertEquals(1, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(3, version.getPatch());
        assertFalse(version.isSnapshot());
        assertEquals("1.2.3", version.getVersion());
    }

    @Test
    @DisplayName("解析带v前缀的版本号")
    void testParseVersionWithPrefix() {
        VersionInfo version = new VersionInfo("v2.0.1");
        assertEquals(2, version.getMajor());
        assertEquals(0, version.getMinor());
        assertEquals(1, version.getPatch());
    }

    @Test
    @DisplayName("解析SNAPSHOT版本")
    void testParseSnapshotVersion() {
        VersionInfo version = new VersionInfo("1.0.0-SNAPSHOT");
        assertTrue(version.isSnapshot());
        assertEquals(1, version.getMajor());
        assertEquals(0, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("SNAPSHOT", version.getPreRelease());
    }

    @Test
    @DisplayName("解析预发布版本")
    void testParsePreReleaseVersion() {
        VersionInfo version = new VersionInfo("2.0.0-alpha.1");
        assertEquals(2, version.getMajor());
        assertEquals(0, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("alpha.1", version.getPreRelease());
    }

    @Test
    @DisplayName("版本比较 - 主版本号")
    void testCompareMajorVersion() {
        VersionInfo v1 = new VersionInfo("1.0.0");
        VersionInfo v2 = new VersionInfo("2.0.0");

        assertTrue(v2.isNewerThan(v1));
        assertTrue(v1.isOlderThan(v2));
        assertEquals(1, v2.compareTo(v1));
        assertEquals(-1, v1.compareTo(v2));
    }

    @Test
    @DisplayName("版本比较 - 次版本号")
    void testCompareMinorVersion() {
        VersionInfo v1 = new VersionInfo("1.1.0");
        VersionInfo v2 = new VersionInfo("1.2.0");

        assertTrue(v2.isNewerThan(v1));
        assertEquals(1, v2.compareTo(v1));
    }

    @Test
    @DisplayName("版本比较 - 补丁版本号")
    void testComparePatchVersion() {
        VersionInfo v1 = new VersionInfo("1.0.1");
        VersionInfo v2 = new VersionInfo("1.0.10");

        assertTrue(v2.isNewerThan(v1));
        assertEquals(1, v2.compareTo(v1));
    }

    @Test
    @DisplayName("版本比较 - 相同版本")
    void testCompareSameVersion() {
        VersionInfo v1 = new VersionInfo("1.2.3");
        VersionInfo v2 = new VersionInfo("1.2.3");

        assertEquals(0, v1.compareTo(v2));
        assertFalse(v1.isNewerThan(v2));
        assertFalse(v1.isOlderThan(v2));
        assertEquals(v1, v2);
    }

    @Test
    @DisplayName("版本比较 - 正式版优先于预发布版")
    void testCompareReleaseVsPreRelease() {
        VersionInfo release = new VersionInfo("1.0.0");
        VersionInfo preRelease = new VersionInfo("1.0.0-beta");

        assertTrue(release.isNewerThan(preRelease));
        assertTrue(preRelease.isOlderThan(release));
    }

    @Test
    @DisplayName("Null比较")
    void testCompareWithNull() {
        VersionInfo v1 = new VersionInfo("1.0.0");
        assertTrue(v1.isNewerThan(null));
        assertEquals(1, v1.compareTo(null));
    }

    @Test
    @DisplayName("Equals和HashCode")
    void testEqualsAndHashCode() {
        VersionInfo v1 = new VersionInfo("1.2.3");
        VersionInfo v2 = new VersionInfo("1.2.3");
        VersionInfo v3 = new VersionInfo("1.2.4");

        assertEquals(v1, v2);
        assertNotEquals(v1, v3);
        assertNotEquals(v1, null);
        assertNotEquals(v1, "1.2.3");

        assertEquals(v1.hashCode(), v2.hashCode());
        assertNotEquals(v1.hashCode(), v3.hashCode());
    }

    @Test
    @DisplayName("ToString返回版本号")
    void testToString() {
        VersionInfo version = new VersionInfo("v2.0.1-SNAPSHOT");
        assertEquals("v2.0.1-SNAPSHOT", version.toString());
    }

    @Test
    @DisplayName("静态parse方法")
    void testStaticParse() {
        VersionInfo version = VersionInfo.parse("3.1.4");
        assertEquals(3, version.getMajor());
        assertEquals(1, version.getMinor());
        assertEquals(4, version.getPatch());
    }

    @Test
    @DisplayName("isNumeric方法")
    void testIsNumeric() {
        assertTrue(VersionInfo.parse("1.0.0").isNumeric());
        assertTrue(VersionInfo.parse("1.2.3").isNumeric());
        assertFalse(VersionInfo.parse("1.0.0-SNAPSHOT").isNumeric());
    }

    @Test
    @DisplayName("isTemporal方法")
    void testIsTemporal() {
        assertFalse(VersionInfo.parse("1.0.0").isTemporal());
    }

    @Test
    @DisplayName("isBoolean方法")
    void testIsBoolean() {
        assertFalse(VersionInfo.parse("1.0.0").isBoolean());
    }

    @Test
    @DisplayName("使用AssertJ的流式断言")
    void testWithAssertJ() {
        VersionInfo version = new VersionInfo("2.1.0");

        assertThat(version.getMajor()).isEqualTo(2);
        assertThat(version.getMinor()).isEqualTo(1);
        assertThat(version.getPatch()).isZero();
        assertThat(version.isSnapshot()).isFalse();
        assertThat(version.isNewerThan(new VersionInfo("1.0.0"))).isTrue();
        assertThat(version.toString()).contains("2.1.0");
    }
}
