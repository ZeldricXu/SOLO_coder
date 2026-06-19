package com.designsystem.service;

import com.designsystem.entity.DocParseRecord;
import com.designsystem.mapper.DocParseRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncrementalDocService 增量文档解析服务测试")
class IncrementalDocServiceTest {

    @Mock
    private DocParseRecordMapper parseRecordMapper;

    @Mock
    private DocumentationService documentationService;

    @InjectMocks
    private IncrementalDocService incrementalDocService;

    private static final Long TEST_VERSION_ID = 100L;
    private static final String TEST_FRAMEWORK = "react";

    @Nested
    @DisplayName("文件Hash计算测试")
    class FileHashTests {

        @Test
        @DisplayName("相同内容应生成相同的SHA-256哈希")
        void shouldGenerateConsistentHashForSameContent() {
            String content = "export interface ButtonProps { label: string; onClick: () => void; }";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

            String hash1 = incrementalDocService.calculateFileHash(bytes);
            String hash2 = incrementalDocService.calculateFileHash(bytes);

            assertNotNull(hash1);
            assertEquals(64, hash1.length());
            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("不同内容应生成不同的哈希")
        void shouldGenerateDifferentHashForDifferentContent() {
            String content1 = "const a = 1;";
            String content2 = "const a = 2;";

            String hash1 = incrementalDocService.calculateFileHash(content1.getBytes(StandardCharsets.UTF_8));
            String hash2 = incrementalDocService.calculateFileHash(content2.getBytes(StandardCharsets.UTF_8));

            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("空内容也应生成有效哈希")
        void shouldGenerateHashForEmptyContent() {
            String hash = incrementalDocService.calculateFileHash(new byte[0]);

            assertNotNull(hash);
            assertFalse(hash.isEmpty());
            assertEquals(64, hash.length());
        }
    }

    @Nested
    @DisplayName("文件变更检测测试")
    class FileChangeDetectionTests {

        @Test
        @DisplayName("新文件应被判定为已变更")
        void shouldDetectNewFileAsChanged() {
            String filePath = "/src/components/NewButton.tsx";
            byte[] content = "export const NewButton = () => <button/>;".getBytes(StandardCharsets.UTF_8);

            when(parseRecordMapper.getFileHash(TEST_VERSION_ID, filePath)).thenReturn(null);

            boolean changed = incrementalDocService.isFileChanged(TEST_VERSION_ID, filePath, content);

            assertTrue(changed);
        }

        @Test
        @DisplayName("内容未变更的文件应被判定为未变更")
        void shouldDetectUnchangedFile() {
            String filePath = "/src/components/Button.tsx";
            String content = "export const Button = () => <button/>;";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String existingHash = incrementalDocService.calculateFileHash(bytes);

            when(parseRecordMapper.getFileHash(TEST_VERSION_ID, filePath)).thenReturn(existingHash);

            boolean changed = incrementalDocService.isFileChanged(TEST_VERSION_ID, filePath, bytes);

            assertFalse(changed);
        }

        @Test
        @DisplayName("内容已变更的文件应被正确识别")
        void shouldDetectModifiedFile() {
            String filePath = "/src/components/Button.tsx";
            String oldContent = "export const Button = () => <button/>;";
            String newContent = "export const Button = () => <button className='btn'/>;";

            String oldHash = incrementalDocService.calculateFileHash(oldContent.getBytes(StandardCharsets.UTF_8));
            byte[] newBytes = newContent.getBytes(StandardCharsets.UTF_8);

            when(parseRecordMapper.getFileHash(TEST_VERSION_ID, filePath)).thenReturn(oldHash);

            boolean changed = incrementalDocService.isFileChanged(TEST_VERSION_ID, filePath, newBytes);

            assertTrue(changed);
        }
    }

    @Nested
    @DisplayName("增量解析测试")
    class IncrementalParseTests {

        private MockMultipartFile unchangedFile;
        private MockMultipartFile changedFile;
        private MockMultipartFile newFile;

        @BeforeEach
        void setUp() {
            String unchangedContent = "export const Unchanged = () => null;";
            String changedContent = "export const Changed = () => <div>Updated</div>;";
            String newFileContent = "export const NewComp = () => <span/>;";

            unchangedFile = new MockMultipartFile(
                    "files", "Unchanged.tsx", "text/plain",
                    unchangedContent.getBytes(StandardCharsets.UTF_8)
            );
            changedFile = new MockMultipartFile(
                    "files", "Changed.tsx", "text/plain",
                    changedContent.getBytes(StandardCharsets.UTF_8)
            );
            newFile = new MockMultipartFile(
                    "files", "NewComponent.tsx", "text/plain",
                    newFileContent.getBytes(StandardCharsets.UTF_8)
            );
        }

        @Test
        @DisplayName("应只解析变更的文件，跳过未变更的文件")
        @SuppressWarnings("unchecked")
        void shouldOnlyParseChangedFiles() throws Exception {
            String unchangedHash = incrementalDocService.calculateFileHash(unchangedFile.getBytes());
            when(parseRecordMapper.getFileHash(eq(TEST_VERSION_ID), eq("Unchanged.tsx")))
                    .thenReturn(unchangedHash);
            when(parseRecordMapper.getFileHash(eq(TEST_VERSION_ID), eq("Changed.tsx")))
                    .thenReturn("old-different-hash");
            when(parseRecordMapper.getFileHash(eq(TEST_VERSION_ID), eq("NewComponent.tsx")))
                    .thenReturn(null);

            when(parseRecordMapper.selectByVersionAndPath(any(), any())).thenReturn(null);
            when(parseRecordMapper.insert(any())).thenReturn(1);
            when(documentationService.extractPropsFromSource(anyLong(), any(), anyString()))
                    .thenReturn(Collections.emptyList());
            when(documentationService.extractDocsFromSource(anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            Map<String, Object> result = incrementalDocService.incrementalParseFiles(
                    TEST_VERSION_ID, TEST_FRAMEWORK,
                    Arrays.asList(unchangedFile, changedFile, newFile),
                    null
            );

            assertNotNull(result);
            assertEquals(3, result.get("totalFiles"));
            assertEquals(2, result.get("changedCount"));
            assertEquals(1, result.get("unchangedCount"));

            Set<String> changedFiles = (Set<String>) result.get("changedFiles");
            assertTrue(changedFiles.contains("Changed.tsx"));
            assertTrue(changedFiles.contains("NewComponent.tsx"));

            Set<String> unchangedFiles = (Set<String>) result.get("unchangedFiles");
            assertTrue(unchangedFiles.contains("Unchanged.tsx"));

            verify(documentationService, times(2)).extractPropsFromSource(anyLong(), any(), eq(TEST_FRAMEWORK));
            verify(documentationService, times(2)).extractDocsFromSource(anyLong(), any());
        }

        @Test
        @DisplayName("解析失败的文件应被记录但不阻塞其他文件")
        @SuppressWarnings("unchecked")
        void shouldRecordFailedFilesWithoutBlocking() throws Exception {
            MockMultipartFile badFile = new MockMultipartFile(
                    "files", "BadSyntax.tsx", "text/plain",
                    "this is not valid typescript {{ { {".getBytes(StandardCharsets.UTF_8)
            );
            MockMultipartFile goodFile = new MockMultipartFile(
                    "files", "GoodComponent.tsx", "text/plain",
                    "export const Good = () => null;".getBytes(StandardCharsets.UTF_8)
            );

            when(parseRecordMapper.selectByVersionAndPath(any(), any())).thenReturn(null);
            when(parseRecordMapper.insert(any())).thenReturn(1);
            when(documentationService.extractPropsFromSource(eq(TEST_VERSION_ID), eq(badFile), anyString()))
                    .thenThrow(new RuntimeException("Parse error"));
            when(documentationService.extractPropsFromSource(eq(TEST_VERSION_ID), eq(goodFile), anyString()))
                    .thenReturn(Collections.emptyList());
            when(documentationService.extractDocsFromSource(eq(TEST_VERSION_ID), eq(goodFile)))
                    .thenReturn(Collections.emptyList());

            Map<String, Object> result = incrementalDocService.incrementalParseFiles(
                    TEST_VERSION_ID, TEST_FRAMEWORK,
                    Arrays.asList(badFile, goodFile),
                    null
            );

            assertEquals(2, result.get("totalFiles"));
            assertEquals(1, result.get("failedCount"));
            assertEquals(1, result.get("changedCount"));

            Set<String> failedFiles = (Set<String>) result.get("failedFiles");
            assertTrue(failedFiles.contains("BadSyntax.tsx"));

            verify(documentationService, times(1)).extractPropsFromSource(eq(TEST_VERSION_ID), eq(goodFile), anyString());
        }

        @Test
        @DisplayName("应记录文件解析统计信息")
        @SuppressWarnings("unchecked")
        void shouldRecordParseStatistics() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "files", "Button.tsx", "text/plain",
                    "export interface ButtonProps { label: string; }".getBytes(StandardCharsets.UTF_8)
            );

            when(parseRecordMapper.selectByVersionAndPath(any(), any())).thenReturn(null);
            when(parseRecordMapper.insert(any())).thenReturn(1);
            when(documentationService.extractPropsFromSource(anyLong(), any(), anyString()))
                    .thenReturn(Collections.nCopies(3, new com.designsystem.entity.ComponentProp()));
            when(documentationService.extractDocsFromSource(anyLong(), any()))
                    .thenReturn(Collections.nCopies(2, new com.designsystem.entity.ComponentDoc()));

            Map<String, Object> result = incrementalDocService.incrementalParseFiles(
                    TEST_VERSION_ID, TEST_FRAMEWORK,
                    Collections.singletonList(file),
                    null
            );

            assertEquals(3, result.get("totalPropsExtracted"));
            assertEquals(2, result.get("totalDocsExtracted"));
            assertNotNull(result.get("durationMs"));
            assertTrue((Long) result.get("durationMs") >= 0);
        }
    }

    @Nested
    @DisplayName("全量重新解析测试")
    class FullReparseTests {

        @Test
        @DisplayName("全量解析应先清除历史记录再重新解析")
        void shouldClearHistoryBeforeFullReparse() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "files", "Button.tsx", "text/plain",
                    "export const Button = () => null;".getBytes(StandardCharsets.UTF_8)
            );

            List<DocParseRecord> oldRecords = Arrays.asList(new DocParseRecord(), new DocParseRecord());
            when(parseRecordMapper.selectByVersionId(TEST_VERSION_ID)).thenReturn(oldRecords);
            when(parseRecordMapper.insert(any())).thenReturn(1);
            when(documentationService.extractPropsFromSource(anyLong(), any(), anyString()))
                    .thenReturn(Collections.emptyList());
            when(documentationService.extractDocsFromSource(anyLong(), any()))
                    .thenReturn(Collections.emptyList());

            incrementalDocService.fullReparse(TEST_VERSION_ID, TEST_FRAMEWORK,
                    Collections.singletonList(file), null);

            verify(parseRecordMapper, times(2)).deleteById(any());
        }
    }

    @Nested
    @DisplayName("解析记录统计测试")
    class ParseStatisticsTests {

        @Test
        @DisplayName("应正确计算解析统计信息")
        @SuppressWarnings("unchecked")
        void shouldCalculateParseStatistics() {
            DocParseRecord success1 = new DocParseRecord();
            success1.setParseStatus("SUCCESS");
            success1.setPropCount(5);
            success1.setDocCount(3);

            DocParseRecord success2 = new DocParseRecord();
            success2.setParseStatus("SUCCESS");
            success2.setPropCount(3);
            success2.setDocCount(2);

            DocParseRecord failed = new DocParseRecord();
            failed.setParseStatus("FAILED");
            failed.setPropCount(0);
            failed.setDocCount(0);

            when(parseRecordMapper.selectByVersionId(TEST_VERSION_ID))
                    .thenReturn(Arrays.asList(success1, success2, failed));

            Map<String, Object> stats = incrementalDocService.getParseStatistics(TEST_VERSION_ID);

            assertEquals(3, stats.get("totalFiles"));
            assertEquals(2L, stats.get("successCount"));
            assertEquals(1L, stats.get("failedCount"));
            assertEquals(8, stats.get("totalProps"));
            assertEquals(5, stats.get("totalDocs"));
        }
    }

    @Nested
    @DisplayName("解析记录CRUD测试")
    class ParseRecordCrudTests {

        @Test
        @DisplayName("应能保存新的解析记录")
        void shouldSaveNewParseRecord() {
            DocParseRecord record = new DocParseRecord();
            record.setComponentVersionId(TEST_VERSION_ID);
            record.setFilePath("/src/Button.tsx");
            record.setFileName("Button.tsx");
            record.setFileHash("abc123");

            when(parseRecordMapper.selectByVersionAndPath(TEST_VERSION_ID, "/src/Button.tsx"))
                    .thenReturn(null);
            when(parseRecordMapper.insert(any())).thenReturn(1);

            DocParseRecord saved = incrementalDocService.saveParseRecord(record);

            assertNotNull(saved);
            verify(parseRecordMapper).insert(any());
            verify(parseRecordMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("应能更新已存在的解析记录")
        void shouldUpdateExistingParseRecord() {
            DocParseRecord existing = new DocParseRecord();
            existing.setId(1L);
            existing.setComponentVersionId(TEST_VERSION_ID);
            existing.setFilePath("/src/Button.tsx");

            DocParseRecord update = new DocParseRecord();
            update.setComponentVersionId(TEST_VERSION_ID);
            update.setFilePath("/src/Button.tsx");
            update.setFileHash("new-hash");

            when(parseRecordMapper.selectByVersionAndPath(TEST_VERSION_ID, "/src/Button.tsx"))
                    .thenReturn(existing);
            when(parseRecordMapper.updateById(any())).thenReturn(1);

            DocParseRecord saved = incrementalDocService.saveParseRecord(update);

            assertNotNull(saved);
            assertEquals(1L, saved.getId());
            verify(parseRecordMapper).updateById(any());
            verify(parseRecordMapper, never()).insert(any());
        }
    }
}
