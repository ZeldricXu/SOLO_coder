package com.company.dbstudio.etl;

import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ImportExportConfig.ValueTransform;
import com.company.dbstudio.etl.service.ImportExportService;
import com.company.dbstudio.etl.service.ImportExportService.ColumnType;
import com.company.dbstudio.test.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("数据导入导出 - 字段类型推断测试")
class ImportExportServiceTypeInferenceTest {

    private ImportExportService importExportService;

    @BeforeEach
    void setUp() {
        importExportService = new ImportExportService();
    }

    @ParameterizedTest
    @CsvSource({
            "123,          INTEGER",
            "0,            INTEGER",
            "-456,         INTEGER",
            "2147483647,   INTEGER",
            "9223372036854775807, LONG",
            "3.14,         FLOAT",
            "2.5e10,       DOUBLE",
            "-123.456,     DOUBLE",
            "true,         BOOLEAN",
            "false,        BOOLEAN",
            "TRUE,         BOOLEAN",
            "FALSE,        BOOLEAN",
            "2024-01-15,   DATE",
            "2024/01/15,   DATE",
            "2024-01-15T10:30:00, TIMESTAMP",
            "2024-01-15 10:30:00, TIMESTAMP",
            "hello,        STRING",
            "'123',        STRING",
            ",             UNKNOWN",
            "'',           STRING",
            "NULL,         UNKNOWN",
            "null,         UNKNOWN"
    })
    @DisplayName("单字段类型推断")
    void inferColumnType_ShouldReturnCorrectType(String value, ColumnType expected) {
        ColumnType result = importExportService.inferColumnType(value);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("NULL或空值返回UNKNOWN")
    void inferColumnType_NullOrEmpty_ShouldReturnUnknown() {
        assertThat(importExportService.inferColumnType(null)).isEqualTo(ColumnType.UNKNOWN);
        assertThat(importExportService.inferColumnType("")).isEqualTo(ColumnType.UNKNOWN);
        assertThat(importExportService.inferColumnType("   ")).isEqualTo(ColumnType.UNKNOWN);
        assertThat(importExportService.inferColumnType("NULL")).isEqualTo(ColumnType.UNKNOWN);
        assertThat(importExportService.inferColumnType("null")).isEqualTo(ColumnType.UNKNOWN);
    }

    @Test
    @DisplayName("多列类型推断 - 混合整数和长整型")
    void inferColumnTypes_MixedIntegerAndLong_ShouldPromoteToLong() {
        List<String[]> rows = Arrays.asList(
                new String[]{"id", "value"},
                new String[]{"1", "2147483647"},
                new String[]{"2", "9223372036854775807"},
                new String[]{"3", "100"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, true);

        assertThat(types).hasSize(2);
        assertThat(types.get(0)).isEqualTo(ColumnType.INTEGER);
        assertThat(types.get(1)).isEqualTo(ColumnType.LONG);
    }

    @Test
    @DisplayName("多列类型推断 - 混合浮点数和双精度")
    void inferColumnTypes_MixedFloatAndDouble_ShouldPromoteToDouble() {
        List<String[]> rows = Arrays.asList(
                new String[]{"price", "rate"},
                new String[]{"3.14", "2.5e10"},
                new String[]{"2.718", "1.5"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, true);

        assertThat(types).hasSize(2);
        assertThat(types.get(0)).isEqualTo(ColumnType.FLOAT);
        assertThat(types.get(1)).isEqualTo(ColumnType.DOUBLE);
    }

    @Test
    @DisplayName("多列类型推断 - 混合数值类型升级为双精度")
    void inferColumnTypes_MixedNumericTypes_ShouldPromoteToDouble() {
        List<String[]> rows = Arrays.asList(
                new String[]{"value"},
                new String[]{"123"},
                new String[]{"45.67"},
                new String[]{"89"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, true);

        assertThat(types).hasSize(1);
        assertThat(types.get(0)).isEqualTo(ColumnType.DOUBLE);
    }

    @Test
    @DisplayName("多列类型推断 - 混合类型降级为字符串")
    void inferColumnTypes_MixedNonNumericTypes_ShouldPromoteToString() {
        List<String[]> rows = Arrays.asList(
                new String[]{"data"},
                new String[]{"123"},
                new String[]{"hello"},
                new String[]{"true"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, true);

        assertThat(types).hasSize(1);
        assertThat(types.get(0)).isEqualTo(ColumnType.STRING);
    }

    @Test
    @DisplayName("多列类型推断 - 完整CSV场景")
    void inferColumnTypes_CompleteCsv_ShouldInferAllTypes() {
        List<String[]> rows = Arrays.asList(
                new String[]{"id", "name", "age", "salary", "hire_date", "active", "last_login"},
                new String[]{"1", "John Doe", "30", "50000.50", "2020-01-15", "true", "2024-01-15T10:30:00"},
                new String[]{"2", "Jane Smith", "25", "45000.75", "2021-06-01", "false", "2024-01-14T15:45:30"},
                new String[]{"3", "Bob Wilson", "35", "60000.00", "2019-03-20", "true", "2024-01-13T09:15:00"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, true);

        assertThat(types).hasSize(7);
        assertThat(types.get(0)).isEqualTo(ColumnType.INTEGER);
        assertThat(types.get(1)).isEqualTo(ColumnType.STRING);
        assertThat(types.get(2)).isEqualTo(ColumnType.INTEGER);
        assertThat(types.get(3)).isEqualTo(ColumnType.DOUBLE);
        assertThat(types.get(4)).isEqualTo(ColumnType.DATE);
        assertThat(types.get(5)).isEqualTo(ColumnType.BOOLEAN);
        assertThat(types.get(6)).isEqualTo(ColumnType.TIMESTAMP);
    }

    @Test
    @DisplayName("多列类型推断 - 无表头")
    void inferColumnTypes_NoHeader_ShouldStartFromFirstRow() {
        List<String[]> rows = Arrays.asList(
                new String[]{"1", "John"},
                new String[]{"2", "Jane"},
                new String[]{"3", "Bob"}
        );

        List<ColumnType> types = importExportService.inferColumnTypes(rows, false);

        assertThat(types).hasSize(2);
        assertThat(types.get(0)).isEqualTo(ColumnType.INTEGER);
        assertThat(types.get(1)).isEqualTo(ColumnType.STRING);
    }

    @Test
    @DisplayName("ColumnType.isNumeric 数字类型判断")
    void columnType_isNumeric_ShouldReturnTrueForNumericTypes() {
        assertThat(ColumnType.INTEGER.isNumeric()).isTrue();
        assertThat(ColumnType.LONG.isNumeric()).isTrue();
        assertThat(ColumnType.FLOAT.isNumeric()).isTrue();
        assertThat(ColumnType.DOUBLE.isNumeric()).isTrue();
        assertThat(ColumnType.DECIMAL.isNumeric()).isTrue();
        assertThat(ColumnType.STRING.isNumeric()).isFalse();
        assertThat(ColumnType.DATE.isNumeric()).isFalse();
    }

    @Test
    @DisplayName("ColumnType.isTemporal 时间类型判断")
    void columnType_isTemporal_ShouldReturnTrueForTemporalTypes() {
        assertThat(ColumnType.DATE.isTemporal()).isTrue();
        assertThat(ColumnType.TIMESTAMP.isTemporal()).isTrue();
        assertThat(ColumnType.STRING.isTemporal()).isFalse();
        assertThat(ColumnType.INTEGER.isTemporal()).isFalse();
    }
}
