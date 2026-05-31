package com.solocoder.platform.storage.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentHashCalculator - 边界值测试")
class ContentHashCalculatorBoundaryTest {

    @InjectMocks
    private ContentHashCalculator contentHashCalculator;

    @Test
    @DisplayName("边界值 - 空内容计算哈希")
    void calculateContentHash_EmptyContent() {
        byte[] emptyContent = new byte[0];

        String hash = contentHashCalculator.calculateContentHash(emptyContent);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("边界值 - 空InputStream计算哈希")
    void calculateContentHash_EmptyInputStream() throws Exception {
        InputStream emptyStream = new ByteArrayInputStream(new byte[0]);

        String hash = contentHashCalculator.calculateContentHash(emptyStream, 0);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("边界值 - 超大内容计算哈希")
    void calculateContentHash_LargeContent() {
        byte[] largeContent = new byte[1024 * 1024];
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        String hash = contentHashCalculator.calculateContentHash(largeContent);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("边界值 - 单字节内容计算哈希")
    void calculateContentHash_SingleByte() {
        byte[] singleByte = new byte[]{0x42};

        String hash = contentHashCalculator.calculateContentHash(singleByte);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
    }

    @Test
    @DisplayName("边界值 - null内容生成ContentId")
    void calculateContentId_NullContent() {
        String ipfsId = contentHashCalculator.calculateContentId(null, "IPFS");

        assertNotNull(ipfsId);
        assertTrue(ipfsId.startsWith("Qm"));
    }

    @Test
    @DisplayName("边界值 - 存储类型大小写不敏感")
    void calculateContentId_CaseInsensitiveStorageType() {
        String id1 = contentHashCalculator.calculateContentId("test".getBytes(), "ipfs");
        String id2 = contentHashCalculator.calculateContentId("test".getBytes(), "IPFS");
        String id3 = contentHashCalculator.calculateContentId("test".getBytes(), "IpFs");

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotNull(id3);
    }

    @Test
    @DisplayName("边界值 - 空存储类型使用默认")
    void calculateContentId_EmptyStorageType() {
        String id = contentHashCalculator.calculateContentId("test".getBytes(), "");

        assertNotNull(id);
        assertTrue(id.startsWith("cid://"));
    }

    @Test
    @DisplayName("边界值 - 非常大的流计算哈希")
    void calculateContentHash_LargeStream() throws Exception {
        int size = 1024 * 1024;
        byte[] largeContent = new byte[size];
        for (int i = 0; i < size; i++) {
            largeContent[i] = (byte) (i % 256);
        }
        InputStream largeStream = new ByteArrayInputStream(largeContent);

        String hash = contentHashCalculator.calculateContentHash(largeStream, size);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
    }

    @Test
    @DisplayName("边界值 - 网关URL处理特殊字符ContentId")
    void getGatewayUrl_SpecialCharactersInContentId() {
        String specialId = "Qm@#$%^&*()";

        String url = contentHashCalculator.getGatewayUrl(specialId, "IPFS");

        assertEquals("https://ipfs.io/ipfs/" + specialId, url);
    }

    @Test
    @DisplayName("边界值 - 空ContentId获取网关URL")
    void getGatewayUrl_EmptyContentId() {
        String url = contentHashCalculator.getGatewayUrl("", "IPFS");

        assertEquals("https://ipfs.io/ipfs/", url);
    }

    @Test
    @DisplayName("边界值 - null存储类型获取网关URL")
    void getGatewayUrl_NullStorageType() {
        String url = contentHashCalculator.getGatewayUrl("Qm123", null);

        assertNotNull(url);
        assertTrue(url.contains("gateway.solocoder.com"));
    }

    @Test
    @DisplayName("边界值 - 空存储类型获取网关URL")
    void getGatewayUrl_EmptyStorageType() {
        String url = contentHashCalculator.getGatewayUrl("Qm123", "");

        assertNotNull(url);
        assertTrue(url.contains("gateway.solocoder.com"));
    }
}
