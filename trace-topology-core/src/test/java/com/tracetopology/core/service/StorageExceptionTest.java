package com.tracetopology.core.service;

import com.tracetopology.common.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageExceptionTest {

    @Test
    void testStorageExceptionBuilder() {
        StorageException exception = StorageException.builder("UPLOAD_FAILED", "文件上传失败")
                .operation("upload")
                .bucket("data-bucket")
                .path("/uploads/test.txt")
                .fileId("file_12345")
                .context("fileSize", 1024)
                .context("contentType", "text/plain")
                .build();

        assertEquals("UPLOAD_FAILED", exception.getCode());
        assertEquals("文件上传失败", exception.getMessage());
        assertEquals("upload", exception.getOperation());
        assertEquals("data-bucket", exception.getBucket());
        assertEquals("/uploads/test.txt", exception.getPath());
        assertEquals("file_12345", exception.getFileId());

        Map<String, Object> context = exception.getFullContext();
        assertNotNull(context);
        assertEquals("upload", context.get("operation"));
        assertEquals("data-bucket", context.get("bucket"));
        assertEquals("/uploads/test.txt", context.get("path"));
        assertEquals("file_12345", context.get("fileId"));
        assertEquals(1024, context.get("fileSize"));
        assertEquals("text/plain", context.get("contentType"));
    }

    @Test
    void testStorageExceptionWithCause() {
        Exception cause = new RuntimeException("Network timeout");
        StorageException exception = StorageException.builder("DOWNLOAD_ERROR", "下载失败")
                .operation("download")
                .fileId("file_001")
                .cause(cause)
                .build();

        assertEquals("DOWNLOAD_ERROR", exception.getCode());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testStorageExceptionOperationId() {
        StorageException exception = StorageException.builder("STORE_FAILED", "存储失败")
                .operation("storeFile")
                .context("operationId", "abc123")
                .build();

        Map<String, Object> context = exception.getFullContext();
        assertEquals("abc123", context.get("operationId"));
    }
}
