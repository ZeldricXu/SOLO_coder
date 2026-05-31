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
@DisplayName("ContentHashCalculator - 正常业务流程测试")
class ContentHashCalculatorNormalFlowTest {

    @InjectMocks
    private ContentHashCalculator contentHashCalculator;

    @Test
    @DisplayName("正常计算内容哈希 - 使用byte[]")
    void calculateContentHash_WithByteArray() {
        String content = "Hello, IPFS!";
        byte[] contentBytes = content.getBytes();

        String hash = contentHashCalculator.calculateContentHash(contentBytes);

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("正常计算内容哈希 - 使用InputStream")
    void calculateContentHash_WithInputStream() throws Exception {
        String content = "Hello, Decentralized Storage!";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());

        String hash = contentHashCalculator.calculateContentHash(inputStream, content.length());

        assertNotNull(hash);
        assertTrue(hash.startsWith("0x"));
        assertEquals(66, hash.length());
    }

    @Test
    @DisplayName("正常生成IPFS CID")
    void calculateContentId_Ipfs() {
        String contentId = contentHashCalculator.calculateContentId("test content".getBytes(), "IPFS");

        assertNotNull(contentId);
        assertTrue(contentId.startsWith("Qm"));
        assertEquals(46, contentId.length());
    }

    @Test
    @DisplayName("正常生成Arweave交易ID")
    void calculateContentId_Arweave() {
        String contentId = contentHashCalculator.calculateContentId("test content".getBytes(), "ARWEAVE");

        assertNotNull(contentId);
        assertTrue(contentId.startsWith("ar://"));
    }

    @Test
    @DisplayName("正常生成Filecoin CID")
    void calculateContentId_Filecoin() {
        String contentId = contentHashCalculator.calculateContentId("test content".getBytes(), "FILECOIN");

        assertNotNull(contentId);
        assertTrue(contentId.startsWith("bafy"));
    }

    @Test
    @DisplayName("正常生成默认CID")
    void calculateContentId_Default() {
        String contentId = contentHashCalculator.calculateContentId("test content".getBytes(), "UNKNOWN");

        assertNotNull(contentId);
        assertTrue(contentId.startsWith("cid://"));
    }

    @Test
    @DisplayName("正常获取IPFS网关URL")
    void getGatewayUrl_Ipfs() {
        String contentId = "QmXYZ123456789";
        String url = contentHashCalculator.getGatewayUrl(contentId, "IPFS");

        assertEquals("https://ipfs.io/ipfs/" + contentId, url);
    }

    @Test
    @DisplayName("正常获取Arweave网关URL")
    void getGatewayUrl_Arweave() {
        String contentId = "ar://abc123def456";
        String url = contentHashCalculator.getGatewayUrl(contentId, "ARWEAVE");

        assertEquals("https://arweave.net/abc123def456", url);
    }

    @Test
    @DisplayName("正常获取Filecoin网关URL")
    void getGatewayUrl_Filecoin() {
        String contentId = "bafyXYZ123";
        String url = contentHashCalculator.getGatewayUrl(contentId, "FILECOIN");

        assertEquals("https://dweb.link/ipfs/" + contentId, url);
    }

    @Test
    @DisplayName("正常获取默认网关URL")
    void getGatewayUrl_Default() {
        String contentId = "cid://xyz123";
        String url = contentHashCalculator.getGatewayUrl(contentId, "UNKNOWN");

        assertEquals("https://gateway.solocoder.com/content/" + contentId, url);
    }

    @Test
    @DisplayName("相同内容产生相同哈希 - 幂等性验证")
    void calculateContentHash_SameContentSameHash() {
        String content = "Deterministic content";
        byte[] contentBytes = content.getBytes();

        String hash1 = contentHashCalculator.calculateContentHash(contentBytes);
        String hash2 = contentHashCalculator.calculateContentHash(contentBytes);

        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("不同内容产生不同哈希 - 碰撞抵抗验证")
    void calculateContentHash_DifferentContentDifferentHash() {
        String content1 = "Content A";
        String content2 = "Content B";

        String hash1 = contentHashCalculator.calculateContentHash(content1.getBytes());
        String hash2 = contentHashCalculator.calculateContentHash(content2.getBytes());

        assertNotEquals(hash1, hash2);
    }
}
