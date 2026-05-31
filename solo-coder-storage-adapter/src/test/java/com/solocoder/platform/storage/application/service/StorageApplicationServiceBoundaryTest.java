package com.solocoder.platform.storage.application.service;

import com.solocoder.platform.storage.domain.model.StoredContent;
import com.solocoder.platform.storage.domain.repository.StoredContentRepository;
import com.solocoder.platform.storage.domain.service.ContentHashCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageApplicationService - 边界值测试")
class StorageApplicationServiceBoundaryTest {

    @Mock
    private ContentHashCalculator contentHashCalculator;

    @Mock
    private StoredContentRepository storedContentRepository;

    @InjectMocks
    private StorageApplicationService storageApplicationService;

    @Test
    @DisplayName("边界值 - 空内容上传")
    void upload_EmptyContent() {
        String emptyContent = "";

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xempty");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmEMPTY");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                emptyContent, "text/plain", "IPFS", "mainnet",
                true, null, null, "user1");

        assertNotNull(result);
        assertEquals(0L, result.getSize());
    }

    @Test
    @DisplayName("边界值 - 超大内容上传")
    void upload_VeryLargeContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("Large content data ");
        }
        String largeContent = sb.toString();

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xlarge");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmLARGE");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                largeContent, "text/plain", "IPFS", "mainnet",
                true, null, null, "user1");

        assertNotNull(result);
        assertEquals(largeContent.getBytes().length, result.getSize());
    }

    @Test
    @DisplayName("边界值 - null网络参数使用默认")
    void upload_NullNetwork() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xabc");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("Qm123");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                "content", "text/plain", "IPFS", null,
                true, null, null, null);

        assertEquals("mainnet", result.getNetwork());
        assertEquals("system", result.getCreatedBy());
    }

    @Test
    @DisplayName("边界值 - 获取不存在的内容")
    void getContentInfo_NonExistentContentId() {
        String nonExistentId = "QmNonExistent";

        when(storedContentRepository.findByContentId(nonExistentId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            storageApplicationService.getContentInfo(nonExistentId);
        });
    }

    @Test
    @DisplayName("边界值 - 空ContentId获取内容")
    void getContentInfo_EmptyContentId() {
        when(storedContentRepository.findByContentId(""))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            storageApplicationService.getContentInfo("");
        });
    }

    @Test
    @DisplayName("边界值 - Pin不存在的内容")
    void pinContent_NonExistentContentId() {
        String nonExistentId = "QmNonExistent";

        when(storedContentRepository.findByContentId(nonExistentId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            storageApplicationService.pinContent(nonExistentId, "local");
        });
    }

    @Test
    @DisplayName("边界值 - 删除不存在的内容")
    void deleteContent_NonExistentContentId() {
        String nonExistentId = "QmNonExistent";

        when(storedContentRepository.deleteByContentId(nonExistentId))
                .thenReturn(false);

        boolean result = storageApplicationService.deleteContent(nonExistentId);

        assertFalse(result);
    }

    @Test
    @DisplayName("边界值 - 超大元数据上传")
    void upload_WithLargeMetadata() {
        Map<String, Object> largeMetadata = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            largeMetadata.put("key" + i, "value" + i);
        }

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xmeta");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmMETA");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                "content", "text/plain", "IPFS", "mainnet",
                true, null, largeMetadata, "user1");

        assertNotNull(result);
        assertNotNull(result.getMetadata());
        assertEquals(100, result.getMetadata().size());
    }

    @Test
    @DisplayName("边界值 - null元数据上传")
    void upload_WithNullMetadata() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xnull");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmNULL");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                "content", "text/plain", "IPFS", "mainnet",
                true, null, null, "user1");

        assertNotNull(result);
        assertNull(result.getMetadata());
    }

    @Test
    @DisplayName("边界值 - 特殊字符内容上传")
    void upload_WithSpecialCharacters() {
        String specialContent = "!@#$%^&*()_+{}|:<>?[]\\;',./`~";

        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xspecial");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmSPECIAL");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                specialContent, "text/plain", "IPFS", "mainnet",
                true, null, null, "user1");

        assertNotNull(result);
        assertEquals(specialContent.getBytes().length, result.getSize());
    }

    @Test
    @DisplayName("边界值 - null Content Type")
    void upload_WithNullContentType() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xnullct");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmNULLCT");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                "content", null, "IPFS", "mainnet",
                true, null, null, "user1");

        assertNotNull(result);
        assertNull(result.getMimeType());
    }

    @Test
    @DisplayName("边界值 - 不Pin内容上传")
    void upload_WithoutPin() {
        when(contentHashCalculator.calculateContentHash(any(byte[].class)))
                .thenReturn("0xnopin");
        when(contentHashCalculator.calculateContentId(any(byte[].class), anyString()))
                .thenReturn("QmNOPIN");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                "content", "text/plain", "IPFS", "mainnet",
                false, null, null, "user1");

        assertEquals(StoredContent.PinStatus.UNPINNED, result.getPinStatus());
    }
}
