package com.projectcollab.service;

import com.projectcollab.builder.TestDataBuilder;
import com.projectcollab.dto.DocumentShareTask;
import com.projectcollab.dto.UploadDocumentRequest;
import com.projectcollab.entity.Document;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.entity.Reminder;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.*;
import com.projectcollab.service.document.DocumentService;
import com.projectcollab.service.queue.DocumentShareQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("文档模块单元测试")
class DocumentModuleTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private DocumentShareQueueService queueService;

    private Project testProject;
    private ProjectMember testMember1;
    private ProjectMember testMember2;

    @BeforeEach
    void setUp() {
        testProject = TestDataBuilder.buildInProgressProject();
        projectRepository.save(testProject);

        testMember1 = TestDataBuilder.buildMember(testProject, "user_001");
        testMember2 = TestDataBuilder.buildMember(testProject, "user_002");
        memberRepository.save(testMember1);
        memberRepository.save(testMember2);

        queueService.clearQueue();
    }

    @Nested
    @DisplayName("文档上传测试")
    class DocumentUploadTests {

        @Test
        @DisplayName("测试文档上传成功")
        void testUploadDocument_Success() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_001", false);

            Document result = documentService.uploadDocument(request);

            assertNotNull(result);
            assertNotNull(result.getDocId());
            assertEquals("测试文档.pdf", result.getDocName());
            assertEquals("general", result.getDocType());
            assertEquals(1024, result.getDocSize());
            assertEquals("user_001", result.getDocUploader());
            assertNotNull(result.getUploadedAt());
            assertNotNull(result.getDocPath());
            assertFalse(result.isShared());
        }

        @Test
        @DisplayName("测试文档上传后持久化")
        void testUploadDocument_Persistence() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "持久化测试.docx", "user_001", false);

            Document uploaded = documentService.uploadDocument(request);

            Optional<Document> found = documentService.getDocumentById(uploaded.getDocId());

            assertTrue(found.isPresent());
            assertEquals("持久化测试.docx", found.get().getDocName());
            assertEquals("user_001", found.get().getDocUploader());
        }

        @Test
        @DisplayName("测试文档上传时设置文档类型")
        void testUploadDocument_DocType() {
            UploadDocumentRequest designRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "设计文档.pdf", "user_001", false);
            designRequest.setDocType("design");

            UploadDocumentRequest testRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试报告.pdf", "user_001", false);
            testRequest.setDocType("test_report");

            Document designDoc = documentService.uploadDocument(designRequest);
            Document testDoc = documentService.uploadDocument(testRequest);

            assertEquals("design", designDoc.getDocType());
            assertEquals("test_report", testDoc.getDocType());
        }

        @Test
        @DisplayName("测试不同大小的文档上传")
        void testUploadDocument_DifferentSizes() {
            UploadDocumentRequest smallRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "小文档.txt", "user_001", false);
            smallRequest.setDocSize(100);

            UploadDocumentRequest largeRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "大文档.iso", "user_001", false);
            largeRequest.setDocSize(1048576);

            Document smallDoc = documentService.uploadDocument(smallRequest);
            Document largeDoc = documentService.uploadDocument(largeRequest);

            assertEquals(100, smallDoc.getDocSize());
            assertEquals(1048576, largeDoc.getDocSize());
        }

        @Test
        @DisplayName("测试项目不存在时上传文档失败")
        void testUploadDocument_ProjectNotFound() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    "non_existent_project", "测试文档.pdf", "user_001", false);

            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> documentService.uploadDocument(request)
            );

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("测试获取项目所有文档")
        void testGetDocumentsByProjectId() {
            UploadDocumentRequest request1 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档1.pdf", "user_001", false);
            UploadDocumentRequest request2 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档2.pdf", "user_001", false);
            UploadDocumentRequest request3 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档3.pdf", "user_002", false);

            documentService.uploadDocument(request1);
            documentService.uploadDocument(request2);
            documentService.uploadDocument(request3);

            List<Document> documents = documentService.getDocumentsByProjectId(testProject.getProjectId());

            assertEquals(3, documents.size());
        }
    }

    @Nested
    @DisplayName("文档共享测试")
    class DocumentShareTests {

        @Test
        @DisplayName("测试文档上传时选择共享")
        void testUploadDocument_WithShareOption() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "共享文档.pdf", "user_001", true);

            Document result = documentService.uploadDocument(request);

            assertTrue(result.isShared());
        }

        @Test
        @DisplayName("测试文档上传时不选择共享")
        void testUploadDocument_WithoutShareOption() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "私有文档.pdf", "user_001", false);

            Document result = documentService.uploadDocument(request);

            assertFalse(result.isShared());
        }

        @Test
        @DisplayName("测试后续将文档设为共享")
        void testShareDocument_Later() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "后期共享文档.pdf", "user_001", false);

            Document uploaded = documentService.uploadDocument(request);
            assertFalse(uploaded.isShared());

            Document shared = documentService.shareDocument(uploaded.getDocId());

            assertTrue(shared.isShared());
        }

        @Test
        @DisplayName("测试获取所有共享文档")
        void testGetSharedDocuments() {
            UploadDocumentRequest sharedRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "共享文档.pdf", "user_001", true);
            UploadDocumentRequest privateRequest = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "私有文档.pdf", "user_001", false);

            documentService.uploadDocument(sharedRequest);
            documentService.uploadDocument(privateRequest);

            List<Document> sharedDocs = documentService.getSharedDocuments(testProject.getProjectId());

            assertEquals(1, sharedDocs.size());
            assertTrue(sharedDocs.get(0).isShared());
        }

        @Test
        @DisplayName("测试文档不存在时共享失败")
        void testShareDocument_DocNotFound() {
            ProjectCollabException exception = assertThrows(
                    ProjectCollabException.class,
                    () -> documentService.shareDocument("non_existent_doc")
            );

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("测试多次共享同一文档")
        void testShareDocument_MultipleTimes() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_001", false);

            Document uploaded = documentService.uploadDocument(request);
            
            Document shared1 = documentService.shareDocument(uploaded.getDocId());
            Document shared2 = documentService.shareDocument(uploaded.getDocId());

            assertTrue(shared1.isShared());
            assertTrue(shared2.isShared());
        }
    }

    @Nested
    @DisplayName("文档异步共享处理测试")
    class DocumentAsyncShareTests {

        @Test
        @DisplayName("测试共享文档上传后任务入队")
        void testQueueTaskOnUpload() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "共享文档.pdf", "user_001", true);

            documentService.uploadDocument(request);

            assertEquals(1, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试私有文档上传后不入队")
        void testNoQueueForPrivateDoc() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "私有文档.pdf", "user_001", false);

            documentService.uploadDocument(request);

            assertEquals(0, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试后续共享时任务入队")
        void testQueueTaskOnLaterShare() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "后期共享.pdf", "user_001", false);

            documentService.uploadDocument(request);
            assertEquals(0, queueService.getQueueSize());

            Optional<Document> uploaded = documentRepository.findAll().stream().findFirst();
            assertTrue(uploaded.isPresent());

            documentService.shareDocument(uploaded.get().getDocId());

            assertEquals(1, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试多次共享多次入队")
        void testMultipleSharesQueueMultiple() {
            UploadDocumentRequest request1 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档1.pdf", "user_001", true);
            UploadDocumentRequest request2 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档2.pdf", "user_001", true);

            documentService.uploadDocument(request1);
            documentService.uploadDocument(request2);

            assertEquals(2, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试队列任务出队")
        void testDequeueTask() throws InterruptedException {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "共享文档.pdf", "user_001", true);

            documentService.uploadDocument(request);
            assertEquals(1, queueService.getQueueSize());

            DocumentShareTask task = queueService.dequeueTask(2, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(task);
            assertEquals("共享文档.pdf", task.getDocumentName());
            assertEquals(testProject.getProjectId(), task.getProjectId());
            assertEquals("user_001", task.getUploaderId());
            assertEquals(0, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试清空队列")
        void testClearQueue() {
            UploadDocumentRequest request1 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档1.pdf", "user_001", true);
            UploadDocumentRequest request2 = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "文档2.pdf", "user_001", true);

            documentService.uploadDocument(request1);
            documentService.uploadDocument(request2);
            assertEquals(2, queueService.getQueueSize());

            queueService.clearQueue();

            assertEquals(0, queueService.getQueueSize());
        }

        @Test
        @DisplayName("测试文档共享任务包含正确信息")
        void testShareTaskContainsCorrectInfo() throws InterruptedException {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试共享文档.docx", "user_001", true);

            Document uploaded = documentService.uploadDocument(request);

            DocumentShareTask task = queueService.dequeueTask(2, java.util.concurrent.TimeUnit.SECONDS);

            assertNotNull(task);
            assertNotNull(task.getTaskId());
            assertEquals(uploaded.getDocId(), task.getDocumentId());
            assertEquals("测试共享文档.docx", task.getDocumentName());
            assertEquals(testProject.getProjectId(), task.getProjectId());
            assertEquals("user_001", task.getUploaderId());
            assertEquals("PENDING", task.getStatus());
            assertEquals(0, task.getRetryCount());
        }
    }

    @Nested
    @DisplayName("文档元数据测试")
    class DocumentMetadataTests {

        @Test
        @DisplayName("测试文档上传者信息记录")
        void testDocumentMetadata_Uploader() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_002", false);

            Document result = documentService.uploadDocument(request);

            assertEquals("user_002", result.getDocUploader());
        }

        @Test
        @DisplayName("测试文档路径生成")
        void testDocumentMetadata_DocPath() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_001", false);

            Document result = documentService.uploadDocument(request);

            assertNotNull(result.getDocPath());
            assertTrue(result.getDocPath().startsWith("/documents/"));
            assertTrue(result.getDocPath().contains(result.getDocId()));
        }

        @Test
        @DisplayName("测试文档上传时间记录")
        void testDocumentMetadata_UploadTime() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_001", false);

            Document result = documentService.uploadDocument(request);

            assertNotNull(result.getUploadedAt());
        }

        @Test
        @DisplayName("测试文档与项目关联")
        void testDocumentMetadata_ProjectAssociation() {
            UploadDocumentRequest request = TestDataBuilder.buildUploadDocumentRequest(
                    testProject.getProjectId(), "测试文档.pdf", "user_001", false);

            Document result = documentService.uploadDocument(request);

            assertEquals(testProject.getProjectId(), result.getProject().getProjectId());
        }
    }
}
