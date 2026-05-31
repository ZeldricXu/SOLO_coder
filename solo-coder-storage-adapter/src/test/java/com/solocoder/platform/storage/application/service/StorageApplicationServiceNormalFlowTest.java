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

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageApplicationService - 正常业务流程测试")
class StorageApplicationServiceNormalFlowTest {

    @Mock
    private ContentHashCalculator contentHashCalculator;

    @Mock
    private StoredContentRepository storedContentRepository;

    @InjectMocks
    private StorageApplicationService storageApplicationService;

    @Test
    @DisplayName("正常上传 - 上传String内容")
    void upload_WithStringContent() {
        String content = "Hello World!";
        String contentType = "text/plain";

        when(contentHashCalculator.calculateContentHash(any(byte[].class))
                .thenReturn("0xabc123");
        when(contentHashCalculator.calculateContentId(any(byte[].class), eq("IPFS")))
                .thenReturn("QmXYZ123");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                content, contentType, "IPFS", "mainnet", true, "local",
                Map.of("key", "value"), "user1");

        assertNotNull(result);
        assertEquals("QmXYZ123", result.getContentId());
        assertEquals("0xabc123", result.getContentHash());
        assertEquals(StoredContent.StorageType.IPFS, result.getStorageType());
        assertEquals(StoredContent.PinStatus.PINNED, result.getPinStatus());
        assertEquals("text/plain", result.getMimeType());
        assertEquals("user1", result.getCreatedBy());

        verify(contentHashCalculator).calculateContentHash(any(byte[].class));
        verify(storedContentRepository).save(any(StoredContent.class));
    }

    @Test
    @DisplayName("正常上传 - 上传byte[]内容")
    void upload_WithByteArrayContent() {
        byte[] content = "Binary data".getBytes();
        String contentType = "application/octet-stream";

        when(contentHashCalculator.calculateContentHash(any(byte[].class))
                .thenReturn("0xdef456");
        when(contentHashCalculator.calculateContentId(any(byte[].class), eq("ARWEAVE")))
                .thenReturn("ar://abc789");
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StoredContent result = storageApplicationService.upload(
                content, contentType, "ARWEAVE", "mainnet", false, null, null, "user2");

        assertNotNull(result);
        assertEquals("ar://abc789", result.getContentId());
        assertEquals(StoredContent.PinStatus.UNPINNED, result.getPinStatus());
    }

    @Test
    @DisplayName("正常获取内容信息")
    void getContentInfo_WithValidContentId() {
        String contentId = "QmXYZ123";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .contentHash("0xabc123")
                .storageType(StoredContent.StorageType.IPFS)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));

        StoredContent result = storageApplicationService.getContentInfo(contentId);

        assertNotNull(result);
        assertEquals(contentId, result.getContentId());
    }

    @Test
    @DisplayName("正常Pin内容")
    void pinContent_WithValidContentId() {
        String contentId = "QmXYZ123";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .pinStatus(StoredContent.PinStatus.UNPINNED)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = storageApplicationService.pinContent(contentId, "local-node");

        assertTrue(result);
    }

    @Test
    @DisplayName("正常Unpin内容")
    void unpinContent_WithValidContentId() {
        String contentId = "QmXYZ123";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .pinStatus(StoredContent.PinStatus.PINNED)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));
        when(storedContentRepository.save(any(StoredContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = storageApplicationService.unpinContent(contentId);

        assertTrue(result);
    }

    @Test
    @DisplayName("正常删除内容")
    void deleteContent_WithValidContentId() {
        String contentId = "QmXYZ123";

        when(storedContentRepository.deleteByContentId(contentId))
                .thenReturn(true);

        boolean result = storageApplicationService.deleteContent(contentId);

        assertTrue(result);
        verify(storedContentRepository).deleteByContentId(contentId);
    }

    @Test
    @DisplayName("正常获取网关URL")
    void getGatewayUrl_WithValidContentId() {
        String contentId = "QmXYZ123";
        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .storageType(StoredContent.StorageType.IPFS)
                .build();

        when(storedContentRepository.findByContentId(contentId))
                .thenReturn(Optional.of(storedContent));
        when(contentHashCalculator.getGatewayUrl(contentId, "IPFS"))
                .thenReturn("https://ipfs.io/ipfs/" + contentId);

        String result = storageApplicationService.getGatewayUrl(contentId);

        assertEquals("https://ipfs.io/ipfs/QmXYZ123", result);
    }
}
