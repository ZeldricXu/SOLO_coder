package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FastFeatureExtractorTest {

    private FastFeatureExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new FastFeatureExtractor();
    }

    @Test
    @DisplayName("特征提取准确性: length, firstByte, 各种计数")
    void testFeatureExtractionAccuracy() {
        String line = "{\"service\":\"api\",\"level\":\"INFO\"}";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = extractor.extract(bytes, 0, bytes.length);

        assertEquals('{', feature.getFirstByte());
        assertFalse(feature.isStartsWithDigit());
        assertEquals(3, feature.getColonCount());
        assertEquals(0, feature.getBracketCount());
        assertEquals(4, feature.getQuoteCount());
        assertEquals(1, feature.getBraceCount());
        assertEquals(0, feature.getPipeCount());
        assertEquals(0, feature.getSpaceCount());
    }

    @Test
    @DisplayName("spaceRatio 计算正确性")
    void testSpaceRatioCalculation() {
        String line = "a b c d e";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = extractor.extract(bytes, 0, bytes.length);

        assertEquals(4, feature.getSpaceCount());
        assertEquals(9, bytes.length);
        assertEquals(4.0 / 9.0, feature.getSpaceRatio(), 0.0001);

        String noSpaces = "abcdefghij";
        byte[] bytes2 = noSpaces.getBytes(StandardCharsets.UTF_8);
        LogFeature feature2 = extractor.extract(bytes2, 0, bytes2.length);
        assertEquals(0.0, feature2.getSpaceRatio(), 0.0001);

        String allSpaces = "    ";
        byte[] bytes3 = allSpaces.getBytes(StandardCharsets.UTF_8);
        LogFeature feature3 = extractor.extract(bytes3, 0, bytes3.length);
        assertEquals(1.0, feature3.getSpaceRatio(), 0.0001);
    }

    @Test
    @DisplayName("firstFieldPattern 应正确捕获前16字节")
    void testFirstFieldPattern() {
        String line = "2024-06-10 10:30:00.123 [main] INFO";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = extractor.extract(bytes, 0, bytes.length);

        byte[] pattern = feature.getFirstFieldPattern();
        assertEquals(16, pattern.length);

        byte[] expectedFirst16 = new byte[16];
        System.arraycopy(bytes, 0, expectedFirst16, 0, 16);
        assertArrayEquals(expectedFirst16, pattern);

        String shortLine = "short";
        byte[] shortBytes = shortLine.getBytes(StandardCharsets.UTF_8);
        LogFeature shortFeature = extractor.extract(shortBytes, 0, shortBytes.length);
        assertEquals(5, shortFeature.getFirstFieldPattern().length);
        assertArrayEquals(shortBytes, shortFeature.getFirstFieldPattern());
    }

    @Test
    @DisplayName("不同编码测试")
    void testDifferentEncodings() {
        String utf8Line = "{\"message\":\"测试中文\"}";
        byte[] utf8Bytes = utf8Line.getBytes(StandardCharsets.UTF_8);
        LogFeature utf8Feature = extractor.extract(utf8Bytes, 0, utf8Bytes.length);
        assertEquals('{', utf8Feature.getFirstByte());
        assertEquals(3, utf8Feature.getBraceCount());
        assertEquals(4, utf8Feature.getQuoteCount());
        assertTrue(utf8Feature.getSpaceRatio() >= 0);

        String isoLine = "service,level,message,200";
        byte[] isoBytes = isoLine.getBytes(StandardCharsets.ISO_8859_1);
        LogFeature isoFeature = extractor.extract(isoBytes, 0, isoBytes.length);
        assertEquals('s', isoFeature.getFirstByte());
        assertEquals(0, isoFeature.getColonCount());
        assertEquals(0, isoFeature.getBraceCount());
    }

    @Test
    @DisplayName("数字开头检测")
    void testStartsWithDigit() {
        String digitLine = "1718000000000,api,INFO,test";
        byte[] digitBytes = digitLine.getBytes(StandardCharsets.UTF_8);
        LogFeature digitFeature = extractor.extract(digitBytes, 0, digitBytes.length);
        assertTrue(digitFeature.isStartsWithDigit());
        assertEquals('1', digitFeature.getFirstByte());

        String letterLine = "service=api";
        byte[] letterBytes = letterLine.getBytes(StandardCharsets.UTF_8);
        LogFeature letterFeature = extractor.extract(letterBytes, 0, letterBytes.length);
        assertFalse(letterFeature.isStartsWithDigit());
        assertEquals('s', letterFeature.getFirstByte());
    }

    @Test
    @DisplayName("边界情况: 特殊字符计数")
    void testSpecialCharacterCounts() {
        String line = "[{\"key\": \"value|value2\"}]";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = extractor.extract(bytes, 0, bytes.length);

        assertEquals(1, feature.getBracketCount());
        assertEquals(2, feature.getBraceCount());
        assertEquals(1, feature.getColonCount());
        assertEquals(4, feature.getQuoteCount());
        assertEquals(1, feature.getPipeCount());
        assertEquals(2, feature.getSpaceCount());
    }

    @Test
    @DisplayName("输入验证: null, 无效offset, 无效length")
    void testInputValidation() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(null, 0, 4));

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(bytes, -1, 4));

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(bytes, 0, -1));

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(bytes, 0, 100));

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(bytes, 0, 0));

        assertThrows(IllegalArgumentException.class, () ->
                extractor.extract(bytes, 10, 1));
    }

    @Test
    @DisplayName("offset和length的部分提取")
    void testPartialExtraction() {
        String line = "prefix{\"key\":\"value\"}suffix";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        int jsonStart = line.indexOf('{');
        int jsonLength = line.lastIndexOf('}') - jsonStart + 1;

        LogFeature feature = extractor.extract(bytes, jsonStart, jsonLength);

        assertEquals('{', feature.getFirstByte());
        assertEquals(2, feature.getColonCount());
        assertEquals(4, feature.getQuoteCount());
        assertEquals(2, feature.getBraceCount());
    }

    @Test
    @DisplayName("Syslog格式特征提取")
    void testSyslogFeatureExtraction() {
        String syslog = "<123>Jun 10 10:30:00 hostname kernel: CPU temp high";
        byte[] bytes = syslog.getBytes(StandardCharsets.UTF_8);
        LogFeature feature = extractor.extract(bytes, 0, bytes.length);

        assertEquals('<', feature.getFirstByte());
        assertFalse(feature.isStartsWithDigit());
        assertEquals(1, feature.getBracketCount());
        assertEquals(2, feature.getColonCount());
        assertTrue(feature.getSpaceRatio() > 0.1);
    }
}
