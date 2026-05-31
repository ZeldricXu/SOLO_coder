package com.cdcsync.fix;

import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.metadata.crawler.JdbcMetadataProvider;
import com.cdcsync.metadata.crawler.StatisticsCalculator;
import com.cdcsync.metadata.domain.ColumnInfo;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.domain.TableInfo;
import com.cdcsync.metadata.mapper.ColumnInfoMapper;
import com.cdcsync.metadata.mapper.DataSourceMapper;
import com.cdcsync.metadata.mapper.SchemaInfoMapper;
import com.cdcsync.metadata.mapper.TableInfoMapper;
import com.cdcsync.metadata.service.impl.MetadataCrawlerServiceImpl;
import com.cdcsync.test.builder.DataSourceBuilder;
import com.cdcsync.test.builder.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("元数据采集爬虫模块 - 边界校验修复验证")
class MetadataCrawlerBoundaryFixTest {

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private SchemaInfoMapper schemaInfoMapper;

    @Mock
    private TableInfoMapper tableInfoMapper;

    @Mock
    private ColumnInfoMapper columnInfoMapper;

    @InjectMocks
    private MetadataCrawlerServiceImpl service;

    private StatisticsCalculator statisticsCalculator;

    @BeforeEach
    void setUp() {
        reset(dataSourceMapper, schemaInfoMapper, tableInfoMapper, columnInfoMapper);
        statisticsCalculator = new StatisticsCalculator();
    }

    @Nested
    @DisplayName("ValidationUtils通用校验验证")
    class ValidationUtilsTests {

        @Test
        @DisplayName("notNull - null值应抛异常")
        void notNull_NullValue_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.notNull(null, "field"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("field");
        }

        @Test
        @DisplayName("notNull - 非null值应通过")
        void notNull_NonNullValue_ShouldPass() {
            assertThatCode(() -> ValidationUtils.notNull("value", "field"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n", "\r"})
        @DisplayName("notBlank - 空值/空白应抛异常")
        void notBlank_BlankValue_ShouldThrow(String value) {
            assertThatThrownBy(() -> ValidationUtils.notBlank(value, "field"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("field");
        }

        @Test
        @DisplayName("notBlank - 正常值应通过")
        void notBlank_NormalValue_ShouldPass() {
            assertThatCode(() -> ValidationUtils.notBlank("test", "field"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("notEmpty - null集合应抛异常")
        void notEmpty_NullCollection_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.notEmpty(null, "field"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("notEmpty - 空集合应抛异常")
        void notEmpty_EmptyCollection_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.notEmpty(Collections.emptyList(), "field"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("notEmpty - 非空集合应通过")
        void notEmpty_NonEmptyCollection_ShouldPass() {
            assertThatCode(() -> ValidationUtils.notEmpty(List.of("a"), "field"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("isPositive - 零值应抛异常")
        void isPositive_Zero_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.isPositive(0, "field"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("isPositive - 负数应抛异常")
        void isPositive_Negative_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.isPositive(-1, "field"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("isPositive - 正数应通过")
        void isPositive_Positive_ShouldPass() {
            assertThatCode(() -> ValidationUtils.isPositive(100, "field"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("maxLength - 超长字符串应抛异常")
        void maxLength_TooLong_ShouldThrow() {
            String longStr = "a".repeat(101);
            assertThatThrownBy(() -> ValidationUtils.maxLength(longStr, 100, "field"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("maxLength - 正常长度应通过")
        void maxLength_NormalLength_ShouldPass() {
            assertThatCode(() -> ValidationUtils.maxLength("test", 100, "field"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validSqlIdentifier - 特殊字符应抛异常")
        void validSqlIdentifier_SpecialChars_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.validSqlIdentifier("table; DROP", "tableName"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validSqlIdentifier - 合法标识符应通过")
        void validSqlIdentifier_Valid_ShouldPass() {
            assertThatCode(() -> ValidationUtils.validSqlIdentifier("valid_table_name123", "tableName"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validPort - 0应抛异常")
        void validPort_Zero_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.validPort(0))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validPort - 负数应抛异常")
        void validPort_Negative_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.validPort(-1))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validPort - 超出范围应抛异常")
        void validPort_OutOfRange_ShouldThrow() {
            assertThatThrownBy(() -> ValidationUtils.validPort(70000))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validPort - 合法端口应通过")
        void validPort_Valid_ShouldPass() {
            assertThatCode(() -> ValidationUtils.validPort(3306))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ValidationUtils.validPort(65535))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validLimit - 0应转为默认值")
        void validLimit_Zero_ShouldReturnDefault() {
            assertThat(ValidationUtils.validLimit(0)).isEqualTo(100);
        }

        @Test
        @DisplayName("validLimit - 负数应转为默认值")
        void validLimit_Negative_ShouldReturnDefault() {
            assertThat(ValidationUtils.validLimit(-1)).isEqualTo(100);
        }

        @Test
        @DisplayName("validLimit - 超出最大值应截断")
        void validLimit_TooLarge_ShouldTruncate() {
            assertThat(ValidationUtils.validLimit(10000)).isEqualTo(1000);
        }

        @Test
        @DisplayName("validLimit - 正常范围应原值返回")
        void validLimit_Normal_ShouldReturnOriginal() {
            assertThat(ValidationUtils.validLimit(50)).isEqualTo(50);
        }

        @Test
        @DisplayName("safeTrim - null应返回空字符串")
        void safeTrim_Null_ShouldReturnEmpty() {
            assertThat(ValidationUtils.safeTrim(null)).isEmpty();
        }

        @Test
        @DisplayName("safeTrim - 空白应返回空字符串")
        void safeTrim_Blank_ShouldReturnEmpty() {
            assertThat(ValidationUtils.safeTrim("  test  ")).isEqualTo("test");
            assertThat(ValidationUtils.safeTrim("  ")).isEmpty();
        }

        @Test
        @DisplayName("safeTruncate - null应返回空字符串")
        void safeTruncate_Null_ShouldReturnEmpty() {
            assertThat(ValidationUtils.safeTruncate(null, 10)).isEmpty();
        }

        @Test
        @DisplayName("safeTruncate - 超长应截断")
        void safeTruncate_TooLong_ShouldTruncate() {
            String longStr = "abcdefghijklmnopqrstuvwxyz";
            String truncated = ValidationUtils.safeTruncate(longStr, 10);
            assertThat(truncated).hasSize(10);
            assertThat(truncated).isEqualTo("abcdefghij");
        }
    }

    @Nested
    @DisplayName("StatisticsCalculator边界处理验证")
    class StatisticsCalculatorTests {

        @Test
        @DisplayName("空采样数据 - 应返回默认统计")
        void calculateStats_EmptySampleData_ShouldReturnDefaults() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"VARCHAR\"}]";
            List<Map<String, Object>> sampleData = Collections.emptyList();

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            assertThat(result).isNotNull();
            assertThat(result).containsKey("col1");

            @SuppressWarnings("unchecked")
            Map<String, Object> colStats = (Map<String, Object>) result.get("col1");
            assertThat(colStats.get("nullCount")).isEqualTo(0);
            assertThat(colStats.get("distinctCount")).isEqualTo(0);
        }

        @Test
        @DisplayName("null采样数据 - 应返回默认统计")
        void calculateStats_NullSampleData_ShouldReturnDefaults() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"VARCHAR\"}]";

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(null, columnsJson);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("包含null行 - 应正确处理并计数")
        void calculateStats_WithNullRows_ShouldHandleCorrectly() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"INT\"}]";
            List<Map<String, Object>> sampleData = new ArrayList<>();
            sampleData.add(null);
            sampleData.add(Map.of("col1", 1));
            sampleData.add(null);
            sampleData.add(Map.of("col1", null));
            sampleData.add(Map.of("col1", 2));

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            @SuppressWarnings("unchecked")
            Map<String, Object> colStats = (Map<String, Object>) result.get("col1");
            assertThat(colStats.get("nullCount")).isEqualTo(3);
            assertThat(colStats.get("min")).isEqualTo(1);
            assertThat(colStats.get("max")).isEqualTo(2);
        }

        @Test
        @DisplayName("数值类型 - 应过滤NaN和Infinity")
        void calculateStats_Numeric_ShouldFilterNaNAndInfinity() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"DOUBLE\"}]";
            List<Map<String, Object>> sampleData = new ArrayList<>();
            sampleData.add(Map.of("col1", 1.0));
            sampleData.add(Map.of("col1", Double.NaN));
            sampleData.add(Map.of("col1", Double.POSITIVE_INFINITY));
            sampleData.add(Map.of("col1", Double.NEGATIVE_INFINITY));
            sampleData.add(Map.of("col1", 5.0));

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            @SuppressWarnings("unchecked")
            Map<String, Object> colStats = (Map<String, Object>) result.get("col1");
            assertThat(colStats.get("min")).isEqualTo(1.0);
            assertThat(colStats.get("max")).isEqualTo(5.0);
            assertThat(colStats.get("avg")).isEqualTo(3.0);
        }

        @Test
        @DisplayName("字符串类型 - 超长字符串应截断")
        void calculateStats_String_ShouldTruncateLongValues() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"VARCHAR\"}]";
            String veryLongString = "a".repeat(10000);
            List<Map<String, Object>> sampleData = new ArrayList<>();
            sampleData.add(Map.of("col1", veryLongString));

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            @SuppressWarnings("unchecked")
            Map<String, Object> colStats = (Map<String, Object>) result.get("col1");
            String minValue = (String) colStats.get("min");
            assertThat(minValue).hasSize(1000);
        }

        @Test
        @DisplayName("混合类型 - 应安全转换")
        void calculateStats_MixedTypes_ShouldHandleSafely() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"INT\"}]";
            List<Map<String, Object>> sampleData = new ArrayList<>();
            sampleData.add(Map.of("col1", "123"));
            sampleData.add(Map.of("col1", 456));
            sampleData.add(Map.of("col1", "not_a_number"));

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("全为NaN - 应正确处理")
        void calculateStats_AllNaN_ShouldHandleCorrectly() {
            String columnsJson = "[{\"name\":\"col1\",\"type\":\"DOUBLE\"}]";
            List<Map<String, Object>> sampleData = new ArrayList<>();
            sampleData.add(Map.of("col1", Double.NaN));
            sampleData.add(Map.of("col1", Double.POSITIVE_INFINITY));

            Map<String, Object> result = statisticsCalculator.calculateColumnStatistics(sampleData, columnsJson);

            @SuppressWarnings("unchecked")
            Map<String, Object> colStats = (Map<String, Object>) result.get("col1");
            assertThat(colStats.get("min")).isNull();
            assertThat(colStats.get("max")).isNull();
            assertThat(colStats.get("avg")).isNull();
        }
    }

    @Nested
    @DisplayName("JdbcMetadataProvider边界处理验证")
    class JdbcMetadataProviderTests {

        @Test
        @DisplayName("表名校验 - 空表名应抛异常")
        void validateTableName_Blank_ShouldThrow() {
            assertThatThrownBy(() -> JdbcMetadataProvider.validateTableName(""))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("表名校验 - 含特殊字符应抛异常")
        void validateTableName_SpecialChars_ShouldThrow() {
            assertThatThrownBy(() -> JdbcMetadataProvider.validateTableName("users; DROP TABLE orders; --"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("表名校验 - 超长表名应抛异常")
        void validateTableName_TooLong_ShouldThrow() {
            String longName = "a".repeat(129);
            assertThatThrownBy(() -> JdbcMetadataProvider.validateTableName(longName))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("表名校验 - 合法表名应通过")
        void validateTableName_Valid_ShouldPass() {
            assertThatCode(() -> JdbcMetadataProvider.validateTableName("valid_table_name"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("closeQuietly - null应安全")
        void closeQuietly_Null_ShouldBeSafe() {
            assertThatCode(() -> JdbcMetadataProvider.closeQuietly(null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("closeQuietly - close抛异常应捕获")
        void closeQuietly_Exception_ShouldCatch() {
            AutoCloseable badCloseable = () -> {
                throw new RuntimeException("Close failed");
            };

            assertThatCode(() -> JdbcMetadataProvider.closeQuietly(badCloseable))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getSampleData - 非法limit应修正")
        void getSampleData_InvalidLimit_ShouldBeCorrected() {
            DataSource dataSource = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withHost("localhost")
                    .withPort(3306)
                    .withDatabaseName("testdb")
                    .withUsername("user")
                    .withPassword("pass")
                    .build();

            JdbcMetadataProvider provider = new JdbcMetadataProvider(dataSource);

            assertThatCode(() -> provider.getSampleData("users", 0))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("MetadataCrawlerService边界处理验证")
    class MetadataCrawlerServiceTests {

        @Test
        @DisplayName("crawlFullSchema - 空dataSourceId应抛异常")
        void crawlFullSchema_BlankId_ShouldThrow() {
            assertThatThrownBy(() -> service.crawlFullSchema(""))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("crawlTable - 空tableName应抛异常")
        void crawlTable_BlankTableName_ShouldThrow() {
            assertThatThrownBy(() -> service.crawlTable("ds1", ""))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("analyzeTable - 空参数应抛异常")
        void analyzeTable_BlankParams_ShouldThrow() {
            assertThatThrownBy(() -> service.analyzeTable("", "table1"))
                    .isInstanceOf(BusinessException.class);

            assertThatThrownBy(() -> service.analyzeTable("ds1", ""))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("getSampleData - 空参数应抛异常")
        void getSampleData_BlankParams_ShouldThrow() {
            assertThatThrownBy(() -> service.getSampleData("", "table1", 100))
                    .isInstanceOf(BusinessException.class);

            assertThatThrownBy(() -> service.getSampleData("ds1", "", 100))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("getDataSource - 不存在的数据源应抛异常")
        void getDataSource_NonExistent_ShouldThrow() {
            when(dataSourceMapper.selectById("non-existent")).thenReturn(null);

            assertThatThrownBy(() -> service.crawlFullSchema("non-existent"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("数据源校验 - 非ACTIVE状态应抛异常")
        void validateDataSource_Inactive_ShouldThrow() {
            DataSource inactiveDs = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds1")
                    .withType("mysql")
                    .withHost("localhost")
                    .withPort(3306)
                    .withDatabaseName("testdb")
                    .withStatus("INACTIVE")
                    .build();

            when(dataSourceMapper.selectById("ds1")).thenReturn(inactiveDs);

            assertThatThrownBy(() -> service.crawlFullSchema("ds1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not active");
        }

        @Test
        @DisplayName("数据源校验 - 不支持的类型应抛异常")
        void validateDataSource_UnsupportedType_ShouldThrow() {
            DataSource unknownDs = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds1")
                    .withType("unknown_db")
                    .withHost("localhost")
                    .withPort(3306)
                    .withDatabaseName("testdb")
                    .withStatus("ACTIVE")
                    .build();

            when(dataSourceMapper.selectById("ds1")).thenReturn(unknownDs);

            assertThatThrownBy(() -> service.crawlFullSchema("ds1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unsupported");
        }

        @Test
        @DisplayName("数据源校验 - 端口非法应抛异常")
        void validateDataSource_InvalidPort_ShouldThrow() {
            DataSource badPortDs = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds1")
                    .withType("mysql")
                    .withHost("localhost")
                    .withPort(99999)
                    .withDatabaseName("testdb")
                    .withStatus("ACTIVE")
                    .build();

            when(dataSourceMapper.selectById("ds1")).thenReturn(badPortDs);

            assertThatThrownBy(() -> service.crawlFullSchema("ds1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("crawlFullSchema - 部分表失败应降级继续")
        void crawlFullSchema_PartialTableFailure_ShouldContinue() {
            DataSource ds = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds1")
                    .withType("mysql")
                    .withHost("localhost")
                    .withPort(3306)
                    .withDatabaseName("testdb")
                    .withStatus("ACTIVE")
                    .build();

            when(dataSourceMapper.selectById("ds1")).thenReturn(ds);

            assertThatThrownBy(() -> service.crawlFullSchema("ds1"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("crawlFullSchema - 部分表失败应降级继续")
        void crawlFullSchema_PartialTableFailure_ShouldContinue2() {
            DataSource ds = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds1")
                    .withType("mysql")
                    .withHost("localhost")
                    .withPort(3306)
                    .withDatabaseName("testdb")
                    .withStatus("ACTIVE")
                    .withUsername("user")
                    .withPassword("pass")
                    .build();

            when(dataSourceMapper.selectById("ds1")).thenReturn(ds);

            assertThatThrownBy(() -> service.crawlFullSchema("ds1"))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("SQL注入防护 - 危险表名应被拦截")
        void crawlTable_SqlInjectionAttempt_ShouldBeBlocked() {
            String dangerousTableName = "users' OR '1'='1";

            assertThatThrownBy(() -> service.crawlTable("ds1", dangerousTableName))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("SQL注入防护验证")
    class SqlInjectionTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "users; DROP TABLE orders",
                "users' OR '1'='1",
                "users` WHERE 1=1--",
                "users UNION SELECT * FROM information_schema.tables",
                "users; EXEC xp_cmdshell 'dir'",
        })
        @DisplayName("SQL注入 - 各种攻击模式应被拦截")
        void validateTableName_SqlInjection_ShouldBeBlocked(String tableName) {
            assertThatThrownBy(() -> JdbcMetadataProvider.validateTableName(tableName))
                    .isInstanceOf(BusinessException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "users",
                "user_profiles",
                "order_items_2024",
                "_private_table",
                "table$with$dollar",
        })
        @DisplayName("合法表名 - 应通过校验")
        void validateTableName_ValidNames_ShouldPass(String tableName) {
            assertThatCode(() -> JdbcMetadataProvider.validateTableName(tableName))
                    .doesNotThrowAnyException();
        }
    }
}
