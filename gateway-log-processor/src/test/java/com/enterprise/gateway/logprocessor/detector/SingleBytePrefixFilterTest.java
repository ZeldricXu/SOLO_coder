package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.enterprise.gateway.logprocessor.parser.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleBytePrefixFilterTest {

    private SingleBytePrefixFilter filter;
    private List<LogParser> parsers;
    private JsonLogParser jsonParser;
    private CsvLogParser csvParser;
    private LogbackLogParser logbackParser;
    private NginxLogParser nginxParser;
    private SyslogParser syslogParser;

    @BeforeEach
    void setUp() {
        jsonParser = new JsonLogParser();
        csvParser = new CsvLogParser();
        logbackParser = new LogbackLogParser();
        nginxParser = new NginxLogParser();
        syslogParser = new SyslogParser();

        parsers = new ArrayList<>();
        parsers.add(jsonParser);
        parsers.add(csvParser);
        parsers.add(logbackParser);
        parsers.add(nginxParser);
        parsers.add(syslogParser);

        filter = new SingleBytePrefixFilter(parsers);
    }

    @Test
    @DisplayName("每个首字节应返回正确的候选解析器")
    void testGetCandidateParsersForEachFirstByte() {
        List<LogParser> jsonCandidates = filter.getCandidateParsers((byte) '{');
        assertEquals(1, jsonCandidates.size());
        assertTrue(jsonCandidates.contains(jsonParser));
        assertEquals(LogFormat.JSON, jsonCandidates.get(0).getFormat());

        List<LogParser> digitCandidates = filter.getCandidateParsers((byte) '0');
        assertEquals(3, digitCandidates.size());
        assertTrue(digitCandidates.contains(csvParser));
        assertTrue(digitCandidates.contains(logbackParser));
        assertTrue(digitCandidates.contains(nginxParser));

        List<LogParser> syslogCandidates = filter.getCandidateParsers((byte) '<');
        assertEquals(1, syslogCandidates.size());
        assertTrue(syslogCandidates.contains(syslogParser));
        assertEquals(LogFormat.SYSLOG, syslogCandidates.get(0).getFormat());
    }

    @Test
    @DisplayName("未知首字节应返回空列表")
    void testUnknownFirstByteReturnsEmpty() {
        List<LogParser> unknownCandidates = filter.getCandidateParsers((byte) 'x');
        assertNotNull(unknownCandidates);
        assertTrue(unknownCandidates.isEmpty());

        List<LogParser> spaceCandidates = filter.getCandidateParsers((byte) ' ');
        assertTrue(spaceCandidates.isEmpty());

        List<LogParser> newlineCandidates = filter.getCandidateParsers((byte) '\n');
        assertTrue(newlineCandidates.isEmpty());
    }

    @Test
    @DisplayName("所有5个解析器测试")
    void testAllParsers() {
        List<LogParser> allParsers = filter.getAllParsers();
        assertEquals(5, allParsers.size());
        assertTrue(allParsers.contains(jsonParser));
        assertTrue(allParsers.contains(csvParser));
        assertTrue(allParsers.contains(logbackParser));
        assertTrue(allParsers.contains(nginxParser));
        assertTrue(allParsers.contains(syslogParser));

        assertThrows(UnsupportedOperationException.class, () ->
                allParsers.add(new JsonLogParser()));
    }

    @Test
    @DisplayName("候选列表不可修改")
    void testCandidateListImmutable() {
        List<LogParser> candidates = filter.getCandidateParsers((byte) '{');
        assertThrows(UnsupportedOperationException.class, () ->
                candidates.add(new JsonLogParser()));

        List<LogParser> allParsers = filter.getCandidateParsers((byte) '0');
        assertThrows(UnsupportedOperationException.class, () ->
                allParsers.remove(0));
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new SingleBytePrefixFilter(null));

        assertThrows(IllegalArgumentException.class, () ->
                new SingleBytePrefixFilter(new ArrayList<>()));

        List<LogParser> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () ->
                new SingleBytePrefixFilter(withNull));
    }

    @Test
    @DisplayName("相同首字节的解析器应正确分组")
    void testSameFirstByteGrouping() {
        List<LogParser> digitParsers = filter.getCandidateParsers((byte) '0');
        assertEquals(3, digitParsers.size());

        List<LogFormat> formats = digitParsers.stream()
                .map(LogParser::getFormat)
                .toList();

        assertTrue(formats.contains(LogFormat.CSV));
        assertTrue(formats.contains(LogFormat.LOGBACK));
        assertTrue(formats.contains(LogFormat.NGINX));
    }

    @Test
    @DisplayName("各种边界首字节测试")
    void testBoundaryFirstBytes() {
        byte[] edgeBytes = new byte[] {0, Byte.MAX_VALUE, Byte.MIN_VALUE, 'A', 'Z', 'a', 'z', '1', '2', '9'};
        for (byte b : edgeBytes) {
            if (b != '{' && b != '<' && b != '0') {
                List<LogParser> candidates = filter.getCandidateParsers(b);
                assertTrue(candidates.isEmpty(),
                        "首字节 " + (char) b + " 应返回空列表");
            }
        }

        List<LogParser> zeroCandidates = filter.getCandidateParsers((byte) '0');
        assertEquals(3, zeroCandidates.size(),
                "首字节 '0' 应返回3个候选解析器");
    }
}
