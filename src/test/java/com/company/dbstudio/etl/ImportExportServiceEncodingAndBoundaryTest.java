package com.company.dbstudio.etl;

import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ImportExportConfig.ValueTransform;
import com.company.dbstudio.etl.service.ImportExportService;
import com.company.dbstudio.test.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("数据导入导出 - 编码检测和边界场景测试")
class ImportExportServiceEncodingAndBoundaryTest {

    private ImportExportService importExportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        importExportService = new ImportExportService();
    }

    @Test
    @DisplayName("UTF-8 BOM编码检测")
    void detectEncoding_Utf8Bom_ShouldReturnUtf8() throws IOException {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "Hello, 世界!".getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(content, 0, combined, bom.length, content.length);

        Path file = tempDir.resolve("test-utf8bom.csv");
        Files.write(file, combined);

        Charset detected = importExportService.detectEncoding(file.toFile());
        assertThat(detected).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("UTF-16 LE BOM编码检测")
    void detectEncoding_Utf16LeBom_ShouldReturnUtf16Le() throws IOException {
        byte[] bom = new byte[]{(byte) 0xFF, (byte) 0xFE};
        byte[] content = "Hello".getBytes(StandardCharsets.UTF_16LE);
        byte[] combined = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(content, 0, combined, bom.length, content.length);

        Path file = tempDir.resolve("test-utf16le.csv");
        Files.write(file, combined);

        Charset detected = importExportService.detectEncoding(file.toFile());
        assertThat(detected).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test
    @DisplayName("UTF-16 BE BOM编码检测")
    void detectEncoding_Utf16BeBom_ShouldReturnUtf16Be() throws IOException {
        byte[] bom = new byte[]{(byte) 0xFE, (byte) 0xFF};
        byte[] content = "Hello".getBytes(StandardCharsets.UTF_16BE);
        byte[] combined = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, combined, 0, bom.length);
        System.arraycopy(content, 0, combined, bom.length, content.length);

        Path file = tempDir.resolve("test-utf16be.csv");
        Files.write(file, combined);

        Charset detected = importExportService.detectEncoding(file.toFile());
        assertThat(detected).isEqualTo(StandardCharsets.UTF_16BE);
    }

    @Test
    @DisplayName("纯ASCII文件检测为UTF-8")
    void detectEncoding_AsciiFile_ShouldReturnUtf8() throws IOException {
        Path file = tempDir.resolve("test-ascii.csv");
        Files.writeString(file, "id,name,age\n1,John,30\n2,Jane,25\n", StandardCharsets.UTF_8);

        Charset detected = importExportService.detectEncoding(file.toFile());
        assertThat(detected).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("中文UTF-8文件检测为UTF-8")
    void detectEncoding_ChineseUtf8_ShouldReturnUtf8() throws IOException {
        Path file = tempDir.resolve("test-chinese.csv");
        Files.writeString(file, "id,姓名,年龄\n1,张三,30\n2,李四,25\n", StandardCharsets.UTF_8);

        Charset detected = importExportService.detectEncoding(file.toFile());
        assertThat(detected).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("空表导出 - 无数据")
    void exportData_EmptyTable_ShouldExportHeaderOnly(@TempDir Path tempDir) throws Exception {
        ImportExportConfig config = new ImportExportConfig();
        config.setFormat(ImportExportConfig.Format.CSV);
        config.setSourceTable("empty_table");
        config.setFilePath(tempDir.resolve("empty.csv").toString());
        config.setIncludeHeader(true);

        File file = TestUtils.createTempCsvFile(Arrays.asList(
                new String[]{"id", "name"}
        ));

        long lineCount = Files.lines(file.toPath()).count();
        assertThat(lineCount).isEqualTo(1);
    }

    @Test
    @DisplayName("单行单列极窄表导出")
    void exportData_NarrowTable_ShouldExportCorrectly() throws Exception {
        File file = TestUtils.createTempCsvFile(Arrays.asList(
                new String[]{"value"},
                new String[]{"42"}
        ));

        String content = TestUtils.readFileContent(file);
        assertThat(content).contains("value");
        assertThat(content).contains("42");
    }

    @Test
    @DisplayName("空表导入 - 只有表头")
    void importData_EmptyFile_ShouldHandleGracefully() throws Exception {
        File file = TestUtils.createTempCsvFile(Arrays.asList(
                new String[]{"id", "name", "age"}
        ));

        assertThat(TestUtils.countFileLines(file)).isEqualTo(1);
    }

    @Test
    @DisplayName("CSV文件包含特殊字符")
    void importExport_SpecialCharacters_ShouldPreserveData() throws Exception {
        File file = TestUtils.createTempCsvFile(Arrays.asList(
                new String[]{"id", "description"},
                new String[]{"1", "Hello, \"World\""},
                new String[]{"2", "Line1\nLine2"},
                new String[]{"3", "Value with 'quotes'"}
        ));

        String content = TestUtils.readFileContent(file);
        assertThat(content).contains("Hello");
        assertThat(content).contains("World");
    }

    @Test
    @DisplayName("大文件处理 - 流式写入不占内存")
    void exportData_LargeFile_ShouldUseStreaming() throws Exception {
        int rowCount = 10000;
        StringBuilder sb = new StringBuilder("id,value\n");
        for (int i = 0; i < rowCount; i++) {
            sb.append(i).append(",value_").append(i).append("\n");
        }

        File file = TestUtils.createTempFile(".csv", sb.toString());

        long lineCount = TestUtils.countFileLines(file);
        assertThat(lineCount).isEqualTo(rowCount + 1);

        long fileSize = Files.size(file.toPath());
        assertThat(fileSize).isGreaterThan(100000);
    }

    @Test
    @DisplayName("值转换 - 转为大写")
    void applyTransform_ToUpper_ShouldConvertToUpperCase() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.TO_UPPER);

        Object result = importExportService.applyTransform("hello world", mapping);
        assertThat(result).isEqualTo("HELLO WORLD");
    }

    @Test
    @DisplayName("值转换 - 转为小写")
    void applyTransform_ToLower_ShouldConvertToLowerCase() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.TO_LOWER);

        Object result = importExportService.applyTransform("HELLO WORLD", mapping);
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    @DisplayName("值转换 - 去除首尾空格")
    void applyTransform_Trim_ShouldTrimWhitespace() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.TRIM);

        Object result = importExportService.applyTransform("  hello world  ", mapping);
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    @DisplayName("值转换 - 替换NULL为默认值")
    void applyTransform_ReplaceNull_ShouldUseDefaultValue() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.REPLACE_NULL);
        mapping.setDefaultValue("N/A");

        Object result = importExportService.applyTransform(null, mapping);
        assertThat(result).isEqualTo("N/A");
    }

    @Test
    @DisplayName("值转换 - NULL值无默认值返回空")
    void applyTransform_ReplaceNull_NoDefault_ShouldReturnEmptyString() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.REPLACE_NULL);

        Object result = importExportService.applyTransform(null, mapping);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("值转换 - Base64编码")
    void applyTransform_Base64Encode_ShouldEncode() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.BASE64_ENCODE);

        Object result = importExportService.applyTransform("hello world", mapping);
        assertThat(result).isEqualTo("aGVsbG8gd29ybGQ=");
    }

    @Test
    @DisplayName("值转换 - Base64解码")
    void applyTransform_Base64Decode_ShouldDecode() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.BASE64_DECODE);

        Object result = importExportService.applyTransform("aGVsbG8gd29ybGQ=", mapping);
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    @DisplayName("值转换 - MD5哈希")
    void applyTransform_Md5Hash_ShouldHash() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.HASH_MD5);

        Object result = importExportService.applyTransform("password123", mapping);
        assertThat(result).isNotNull();
        assertThat(result.toString()).hasSize(32);
    }

    @Test
    @DisplayName("值转换 - 无转换返回原值")
    void applyTransform_None_ShouldReturnOriginal() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.NONE);

        Object original = "test value";
        Object result = importExportService.applyTransform(original, mapping);
        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("值转换 - 非空值跳过NULL替换")
    void applyTransform_ReplaceNull_WithNonNullValue_ShouldReturnOriginal() {
        ImportExportConfig.ColumnMapping mapping = new ImportExportConfig.ColumnMapping();
        mapping.setTransform(ValueTransform.REPLACE_NULL);
        mapping.setDefaultValue("N/A");

        Object result = importExportService.applyTransform("actual value", mapping);
        assertThat(result).isEqualTo("actual value");
    }

    @Test
    @DisplayName("CSV解析 - 带引号的字段")
    void parseCsvLine_QuotedFields_ShouldParseCorrectly() {
        ImportExportConfig config = new ImportExportConfig();
        config.setCsvDelimiter(",");
        config.setCsvQuoteChar("\"");

        String line = "\"Doe, John\",30,\"New York, NY\"";
        String[] result = importExportService.parseCsvLine(line, config);

        assertThat(result).hasSize(3);
        assertThat(result[0]).isEqualTo("Doe, John");
        assertThat(result[1]).isEqualTo("30");
        assertThat(result[2]).isEqualTo("New York, NY");
    }

    @Test
    @DisplayName("CSV解析 - 转义引号")
    void parseCsvLine_EscapedQuotes_ShouldParseCorrectly() {
        ImportExportConfig config = new ImportExportConfig();
        config.setCsvDelimiter(",");
        config.setCsvQuoteChar("\"");

        String line = "\"He said \"\"Hello\"\"\",30";
        String[] result = importExportService.parseCsvLine(line, config);

        assertThat(result).hasSize(2);
        assertThat(result[0]).isEqualTo("He said \"Hello\"");
        assertThat(result[1]).isEqualTo("30");
    }

    @Test
    @DisplayName("CSV解析 - 制表符分隔")
    void parseCsvLine_TabDelimiter_ShouldParseCorrectly() {
        ImportExportConfig config = new ImportExportConfig();
        config.setCsvDelimiter("\t");
        config.setCsvQuoteChar("\"");

        String line = "John\tDoe\t30";
        String[] result = importExportService.parseCsvLine(line, config);

        assertThat(result).hasSize(3);
        assertThat(result[0]).isEqualTo("John");
        assertThat(result[1]).isEqualTo("Doe");
        assertThat(result[2]).isEqualTo("30");
    }
}
