package com.configcenter.version.service;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson.JSON;
import com.configcenter.common.entity.ConfigVersion;
import com.configcenter.common.testdata.TestDataBuilder;
import com.configcenter.version.config.VersionCompressionProperties;
import com.configcenter.version.entity.VersionCompressionArchive;
import com.configcenter.version.repository.ConfigVersionRepository;
import com.configcenter.version.repository.VersionCompressionArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("版本压缩服务单元测试")
class VersionCompressionServiceTest {

    @Mock
    private ConfigVersionRepository configVersionRepository;

    @Mock
    private VersionCompressionArchiveRepository archiveRepository;

    @Mock
    private VersionCompressionProperties properties;

    @InjectMocks
    private VersionCompressionService compressionService;

    @BeforeEach
    void setUp() {
        when(properties.getEnabled()).thenReturn(true);
        when(properties.getCompressThresholdVersions()).thenReturn(50);
        when(properties.getKeepLatestVersions()).thenReturn(20);
        when(properties.getMinCompressionSize()).thenReturn(100);
        when(properties.getCompressionAlgorithm()).thenReturn("GZIP");
    }

    @Test
    @DisplayName("测试版本压缩 - 压缩后数据量减少")
    void testCompression_ReduceDataSize() throws Exception {
        String configId = "config_db_01";
        int totalVersions = 100;
        int keepVersions = 20;
        int compressVersions = totalVersions - keepVersions;

        List<ConfigVersion> allVersions = TestDataBuilder.createVersionHistory(configId, totalVersions);
        String jsonData = JSON.toJSONString(allVersions.subList(keepVersions, allVersions.size()));
        byte[] originalBytes = jsonData.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(originalBytes);
            gzipOut.finish();
        }
        byte[] compressedBytes = baos.toByteArray();

        long originalSize = originalBytes.length;
        long compressedSize = compressedBytes.length;

        assertTrue(compressedSize < originalSize, "压缩后的数据大小应该小于原始大小");
        assertTrue(compressedSize > 0, "压缩后的数据大小应该大于0");

        double compressionRatio = (double) compressedSize / originalSize;
        assertTrue(compressionRatio < 1.0, "压缩比应该小于1");

        System.out.printf("原始大小: %d bytes, 压缩后: %d bytes, 压缩比: %.2f%n", 
                originalSize, compressedSize, compressionRatio);
    }

    @Test
    @DisplayName("测试版本压缩 - 版本数量低于阈值时不压缩")
    void testCompression_BelowThreshold() {
        String configId = "config_db_01";
        int versionsBelowThreshold = 30;

        List<ConfigVersion> versions = TestDataBuilder.createVersionHistory(configId, versionsBelowThreshold);
        when(configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId)).thenReturn(versions);

        Map<String, Object> result = compressionService.compressVersions(configId, "admin_001");

        assertNotNull(result);
        assertEquals(false, result.get("compressed"));
        assertEquals("below threshold", result.get("reason"));

        verify(archiveRepository, never()).save(any(VersionCompressionArchive.class));
        verify(configVersionRepository, never()).delete(any(ConfigVersion.class));
    }

    @Test
    @DisplayName("测试版本压缩 - 版本数量超过阈值时压缩")
    void testCompression_AboveThreshold() throws Exception {
        String configId = "config_db_01";
        int totalVersions = 100;
        int keepVersions = 20;
        int compressVersions = totalVersions - keepVersions;

        List<ConfigVersion> allVersions = TestDataBuilder.createVersionHistory(configId, totalVersions);
        when(configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId)).thenReturn(allVersions);
        when(archiveRepository.save(any(VersionCompressionArchive.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = compressionService.compressVersions(configId, "admin_001");

        assertNotNull(result);
        assertEquals(true, result.get("compressed"));
        assertEquals(compressVersions, result.get("versionsCompressed"));
        assertEquals(keepVersions, result.get("remainingVersions"));

        verify(archiveRepository, times(1)).save(any(VersionCompressionArchive.class));
        verify(configVersionRepository, times(compressVersions)).delete(any(ConfigVersion.class));
    }

    @Test
    @DisplayName("测试版本回滚 - 压缩后的版本恢复完整性")
    void testRestoreVersions_Integrity() throws Exception {
        String configId = "config_db_01";
        String archiveId = "archive_001";
        int versionCount = 50;

        List<ConfigVersion> originalVersions = TestDataBuilder.createVersionHistory(configId, versionCount);
        String originalJson = JSON.toJSONString(originalVersions);
        String checksum = SecureUtil.sha256(originalJson);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(originalJson.getBytes(StandardCharsets.UTF_8));
            gzipOut.finish();
        }
        byte[] compressedData = baos.toByteArray();

        VersionCompressionArchive archive = VersionCompressionArchive.builder()
                .archiveId(archiveId)
                .configId(configId)
                .fromVersion("v1")
                .toVersion("v" + versionCount)
                .versionCount(versionCount)
                .compressedData(compressedData)
                .compressionAlgorithm("GZIP")
                .originalSize((long) originalJson.getBytes().length)
                .compressedSize((long) compressedData.length)
                .compressionRatio((double) compressedData.length / originalJson.getBytes().length)
                .checksum(checksum)
                .build();

        when(archiveRepository.findById(archiveId)).thenReturn(Optional.of(archive));

        List<ConfigVersion> restoredVersions = compressionService.restoreVersions(configId, archiveId);

        assertNotNull(restoredVersions);
        assertEquals(versionCount, restoredVersions.size());

        for (int i = 0; i < versionCount; i++) {
            ConfigVersion original = originalVersions.get(i);
            ConfigVersion restored = restoredVersions.get(i);
            assertEquals(original.getVersionId(), restored.getVersionId());
            assertEquals(original.getVersion(), restored.getVersion());
            assertEquals(original.getConfigValue(), restored.getConfigValue());
            assertEquals(original.getChangedBy(), restored.getChangedBy());
        }
    }

    @Test
    @DisplayName("测试数据恢复 - 压缩解压数据一致性")
    void testDataRecovery_Consistency() throws Exception {
        String originalData = TestDataBuilder.createCompressibleConfigValue();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(originalData.getBytes(StandardCharsets.UTF_8));
            gzipOut.finish();
        }
        byte[] compressed = baos.toByteArray();

        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gzipIn = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
        String restoredData = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertEquals(originalData, restoredData, "解压后的数据应该与原始数据完全一致");
    }

    @Test
    @DisplayName("测试压缩策略执行 - 正确识别需要压缩的版本")
    void testCompressionStrategy_IdentifyVersionsToCompress() {
        String configId = "config_db_01";
        int totalVersions = 100;
        int keepVersions = 20;
        int compressVersions = totalVersions - keepVersions;

        List<ConfigVersion> allVersions = TestDataBuilder.createVersionHistory(configId, totalVersions);
        Collections.reverse(allVersions);

        List<ConfigVersion> versionsToKeep = allVersions.subList(0, keepVersions);
        List<ConfigVersion> versionsToCompress = allVersions.subList(keepVersions, allVersions.size());

        assertEquals(keepVersions, versionsToKeep.size());
        assertEquals(compressVersions, versionsToCompress.size());

        assertEquals("v" + totalVersions, versionsToKeep.get(0).getVersion());
        assertEquals("v" + (totalVersions - keepVersions + 1), versionsToKeep.get(keepVersions - 1).getVersion());

        assertEquals("v" + (totalVersions - keepVersions), versionsToCompress.get(0).getVersion());
        assertEquals("v1", versionsToCompress.get(versionsToCompress.size() - 1).getVersion());
    }

    @Test
    @DisplayName("测试压缩统计 - 正确计算压缩统计信息")
    void testCompressionStatistics() {
        String configId = "config_db_01";
        int activeVersions = 20;

        List<ConfigVersion> activeList = TestDataBuilder.createVersionHistory(configId, activeVersions);
        when(configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId)).thenReturn(activeList);

        List<VersionCompressionArchive> archives = new ArrayList<>();
        archives.add(VersionCompressionArchive.builder()
                .archiveId("archive_1")
                .configId(configId)
                .fromVersion("v1")
                .toVersion("v50")
                .versionCount(50)
                .originalSize(100000L)
                .compressedSize(30000L)
                .compressionRatio(0.3)
                .build());
        archives.add(VersionCompressionArchive.builder()
                .archiveId("archive_2")
                .configId(configId)
                .fromVersion("v51")
                .toVersion("v80")
                .versionCount(30)
                .originalSize(60000L)
                .compressedSize(20000L)
                .compressionRatio(0.33)
                .build());
        when(archiveRepository.findByConfigIdOrderByArchiveTimeDesc(configId)).thenReturn(archives);

        Map<String, Object> stats = compressionService.getCompressionStatistics(configId);

        assertEquals(activeVersions, stats.get("activeVersions"));
        assertEquals(80, stats.get("archivedVersions"));
        assertEquals(activeVersions + 80, stats.get("totalVersions"));
        assertEquals(2, stats.get("archiveCount"));
        assertEquals(160000L, stats.get("totalOriginalSize"));
        assertEquals(50000L, stats.get("totalCompressedSize"));
        assertEquals(110000L, stats.get("totalSpaceSaved"));

        double expectedRatio = 50000.0 / 160000.0;
        assertEquals(expectedRatio, (Double) stats.get("overallCompressionRatio"), 0.0001);
    }

    @Test
    @DisplayName("测试压缩禁用时的行为")
    void testCompressionDisabled() {
        String configId = "config_db_01";

        when(properties.getEnabled()).thenReturn(false);

        Map<String, Object> result = compressionService.compressVersions(configId, "admin_001");

        assertNotNull(result);
        assertEquals(false, result.get("compressed"));
        assertEquals("compression disabled", result.get("reason"));

        verify(configVersionRepository, never()).findByConfigIdOrderByChangedAtDesc(anyString());
        verify(archiveRepository, never()).save(any(VersionCompressionArchive.class));
    }

    @Test
    @DisplayName("测试数据校验失败时抛出异常")
    void testRestoreVersions_ChecksumMismatch() {
        String configId = "config_db_01";
        String archiveId = "archive_001";

        List<ConfigVersion> versions = TestDataBuilder.createVersionHistory(configId, 10);
        String jsonData = JSON.toJSONString(versions);
        String correctChecksum = SecureUtil.sha256(jsonData);
        String wrongChecksum = correctChecksum.replace('a', 'b');

        byte[] compressedData;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(jsonData.getBytes(StandardCharsets.UTF_8));
            gzipOut.finish();
            compressedData = baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        VersionCompressionArchive archive = VersionCompressionArchive.builder()
                .archiveId(archiveId)
                .configId(configId)
                .compressedData(compressedData)
                .checksum(wrongChecksum)
                .build();

        when(archiveRepository.findById(archiveId)).thenReturn(Optional.of(archive));

        assertThrows(com.configcenter.common.exception.BusinessException.class, () -> {
            compressionService.restoreVersions(configId, archiveId);
        });
    }

    @Test
    @DisplayName("测试获取归档列表")
    void testGetArchives() {
        String configId = "config_db_01";
        List<VersionCompressionArchive> archives = Arrays.asList(
                VersionCompressionArchive.builder().archiveId("archive_1").configId(configId).build(),
                VersionCompressionArchive.builder().archiveId("archive_2").configId(configId).build()
        );

        when(archiveRepository.findByConfigIdOrderByArchiveTimeDesc(configId)).thenReturn(archives);

        List<VersionCompressionArchive> result = compressionService.getArchives(configId);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试检查是否满足压缩条件")
    void testIsCompressionEligible() {
        String configId = "config_db_01";

        when(properties.getEnabled()).thenReturn(true);
        when(properties.getCompressThresholdVersions()).thenReturn(50);

        List<ConfigVersion> versionsAboveThreshold = TestDataBuilder.createVersionHistory(configId, 100);
        when(configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId)).thenReturn(versionsAboveThreshold);
        assertTrue(compressionService.isCompressionEligible(configId));

        List<ConfigVersion> versionsBelowThreshold = TestDataBuilder.createVersionHistory(configId, 30);
        when(configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId)).thenReturn(versionsBelowThreshold);
        assertFalse(compressionService.isCompressionEligible(configId));

        when(properties.getEnabled()).thenReturn(false);
        assertFalse(compressionService.isCompressionEligible(configId));
    }
}
