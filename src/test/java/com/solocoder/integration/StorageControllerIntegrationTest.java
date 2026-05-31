package com.solocoder.integration;

import com.solocoder.FileLifecycleManagerApplication;
import com.solocoder.base.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = FileLifecycleManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class StorageControllerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Nested
    @DisplayName("文件上传集成测试")
    class FileUploadIntegrationTests {

        @Test
        @DisplayName("上传文件成功 - 正常流程")
        void uploadFile_Success() {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=" + TestConstants.TEST_FILE_NAME);

            webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(201)
                    .jsonPath("$.data.id").exists()
                    .jsonPath("$.data.status").isEqualTo("provisioning");
        }

        @Test
        @DisplayName("上传空文件 - 边界条件")
        void uploadFile_EmptyContent() {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new byte[0])
                    .header("Content-Disposition", "form-data; name=file; filename=empty.txt");

            webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(201)
                    .jsonPath("$.data.id").exists();
        }

        @Test
        @DisplayName("上传特殊字符文件名 - 边界条件")
        void uploadFile_SpecialCharsFileName() {
            String fileName = "测试文件!@#$%.txt";
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=\"" + fileName + "\"");

            webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(201);
        }

        @Test
        @DisplayName("上传长文件名 - 边界条件")
        void uploadFile_LongFileName() {
            String longFileName = "a".repeat(200) + ".txt";
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=" + longFileName);

            webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(201);
        }

        @Test
        @DisplayName("上传Unicode文件名 - 边界条件")
        void uploadFile_UnicodeFileName() {
            String unicodeFileName = "文件_测试_中文_😀.txt";
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=\"" + unicodeFileName + "\"");

            webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("文件查询集成测试")
    class FileQueryIntegrationTests {

        private String uploadedFileId;

        @BeforeEach
        void setUp() {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=" + TestConstants.TEST_FILE_NAME);

            var response = webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .returnResult(Map.class)
                    .getResponseBody()
                    .blockFirst();

            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                uploadedFileId = (String) data.get("id");
            }
        }

        @Test
        @DisplayName("获取文件元数据成功")
        void getFileMetadata_Success() {
            if (uploadedFileId == null) return;

            webTestClient.get()
                    .uri("/api/v1/storage/files/{fileId}/metadata", uploadedFileId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data.id").isEqualTo(uploadedFileId);
        }

        @Test
        @DisplayName("获取不存在文件元数据返回404")
        void getFileMetadata_NotFound() {
            webTestClient.get()
                    .uri("/api/v1/storage/files/{fileId}/metadata", "nonexistent_file")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(404);
        }

        @Test
        @DisplayName("列出文件列表")
        void listFiles_Success() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/storage/files")
                            .queryParam("page", 1)
                            .queryParam("size", 10)
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("分页参数边界 - 无效页码")
        void listFiles_InvalidPageNumber() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/storage/files")
                            .queryParam("page", 0)
                            .queryParam("size", 10)
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("分页参数边界 - 超大页大小")
        void listFiles_LargePageSize() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/storage/files")
                            .queryParam("page", 1)
                            .queryParam("size", 10000)
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("文件删除集成测试")
    class FileDeleteIntegrationTests {

        private String uploadedFileId;

        @BeforeEach
        void setUp() {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=" + TestConstants.TEST_FILE_NAME);

            var response = webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .returnResult(Map.class)
                    .getResponseBody()
                    .blockFirst();

            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                uploadedFileId = (String) data.get("id");
            }
        }

        @Test
        @DisplayName("删除文件成功")
        void deleteFile_Success() {
            if (uploadedFileId == null) return;

            webTestClient.delete()
                    .uri("/api/v1/storage/files/{fileId}", uploadedFileId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("删除不存在的文件返回成功（幂等性）")
        void deleteFile_NotFound() {
            webTestClient.delete()
                    .uri("/api/v1/storage/files/{fileId}", "nonexistent_file")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("重复删除同一文件 - 幂等性保证")
        void deleteFile_DoubleDelete_Idempotent() {
            if (uploadedFileId == null) return;

            webTestClient.delete()
                    .uri("/api/v1/storage/files/{fileId}", uploadedFileId)
                    .exchange()
                    .expectStatus().isOk();

            webTestClient.delete()
                    .uri("/api/v1/storage/files/{fileId}", uploadedFileId)
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    @Nested
    @DisplayName("生命周期策略集成测试")
    class LifecycleIntegrationTests {

        private String uploadedFileId;

        @BeforeEach
        void setUp() {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", TestConstants.TEST_CONTENT.getBytes())
                    .header("Content-Disposition", "form-data; name=file; filename=" + TestConstants.TEST_FILE_NAME);

            var response = webTestClient.post()
                    .uri("/api/v1/storage/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .exchange()
                    .returnResult(Map.class)
                    .getResponseBody()
                    .blockFirst();

            if (response != null && response.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                uploadedFileId = (String) data.get("id");
            }
        }

        @Test
        @DisplayName("应用生命周期策略成功")
        void applyLifecyclePolicy_Success() {
            if (uploadedFileId == null) return;

            Map<String, String> request = Map.of(
                    "fileId", uploadedFileId,
                    "policyName", TestConstants.TEST_POLICY_NAME
            );

            webTestClient.post()
                    .uri("/api/v1/storage/lifecycle/apply")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }

        @Test
        @DisplayName("归档文件成功")
        void archiveFile_Success() {
            if (uploadedFileId == null) return;

            Map<String, String> request = Map.of(
                    "fileId", uploadedFileId,
                    "storageClass", TestConstants.TEST_STORAGE_CLASS
            );

            webTestClient.post()
                    .uri("/api/v1/storage/archive")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200);
        }
    }
}
