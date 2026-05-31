package com.cdcsync.metadata;

import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetadataCrawler 超时降级行为测试")
class MetadataCrawlerTimeoutTest {

    @Mock
    private SchemaInfoMapper schemaInfoMapper;

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private TableInfoMapper tableInfoMapper;

    @InjectMocks
    private MetadataCrawlerServiceImpl service;

    private DataSource validDataSource;

    @BeforeEach
    void setUp() {
        validDataSource = DataSourceBuilder.aDataSource()
                .withDefaults()
                .withId("ds-001")
                .withType("mysql")
                .withHost("localhost")
                .withPort(3306)
                .withDatabaseName("test")
                .withUsername("root")
                .withPassword("root")
                .build();
    }

    @Nested
    @DisplayName("数据源连接超时测试")
    class ConnectionTimeoutTests {

        @Test
        @DisplayName("连接数据库失败 - 应抛出BusinessException")
        void crawlSchema_ConnectionFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenThrow(new SQLException("Connection refused"));

                assertThatThrownBy(() -> service.crawlFullSchema(validDataSource.getId()))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to crawl schema");
            }
        }

        @Test
        @DisplayName("连接无效数据库类型 - 应抛出异常")
        void crawlSchema_InvalidDbType_ShouldThrowException() {
            DataSource invalidSource = DataSourceBuilder.aDataSource()
                    .withDefaults()
                    .withId("ds-invalid")
                    .withType("unsupported")
                    .build();

            when(dataSourceMapper.selectById("ds-invalid")).thenReturn(invalidSource);

            assertThatThrownBy(() -> service.crawlFullSchema("ds-invalid"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unsupported database type");
        }

        @Test
        @DisplayName("数据源不存在 - 应抛出异常")
        void crawlSchema_DataSourceNotFound_ShouldThrowException() {
            when(dataSourceMapper.selectById("non-existent")).thenReturn(null);

            assertThatThrownBy(() -> service.crawlFullSchema("non-existent"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("DataSource not found");
        }
    }

    @Nested
    @DisplayName("查询超时降级测试")
    class QueryTimeoutFallbackTests {

        @Test
        @DisplayName("获取表列表失败 - 应降级并抛出异常")
        void listTables_QueryFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.getMetaData()).thenThrow(new SQLException("Metadata query timeout"));

                assertThatThrownBy(() -> service.listTables(validDataSource.getId()))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to list tables");

                verify(mockConn, times(1)).close();
            }
        }

        @Test
        @DisplayName("获取表信息失败 - 应抛出异常")
        void getTableInfo_QueryFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.getMetaData()).thenThrow(new SQLException("Table metadata query timeout"));

                assertThatThrownBy(() -> service.getTableInfo(validDataSource.getId(), "users"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to get table info");

                verify(mockConn, times(1)).close();
            }
        }

        @Test
        @DisplayName("获取统计信息失败 - 应抛出异常")
        void analyzeTable_QueryFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.createStatement()).thenThrow(new SQLException("Statistics query timeout"));

                assertThatThrownBy(() -> service.analyzeTable(validDataSource.getId(), "users"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to get table statistics");

                verify(mockConn, times(1)).close();
            }
        }
    }

    @Nested
    @DisplayName("事务一致性测试")
    class TransactionConsistencyTests {

        @Test
        @DisplayName("全量采集 - 部分表失败不应影响已采集的表")
        @Timeout(10)
        void crawlFullSchema_PartialFailure_ShouldHandleGracefully() throws Exception {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);

                var metaData = mock(java.sql.DatabaseMetaData.class);
                when(mockConn.getMetaData()).thenReturn(metaData);

                var tablesRs = mock(java.sql.ResultSet.class);
                when(metaData.getTables(any(), any(), any(), any())).thenReturn(tablesRs);
                when(tablesRs.next()).thenReturn(true, true, false);
                when(tablesRs.getString("TABLE_NAME")).thenReturn("users", "orders");

                when(schemaInfoMapper.selectOne(any())).thenReturn(null);
                when(schemaInfoMapper.insert(any(SchemaInfo.class))).thenReturn(1);

                when(tableInfoMapper.selectOne(any())).thenReturn(null);
                when(tableInfoMapper.insert(any(TableInfo.class))).thenReturn(0, 1);

                assertThatThrownBy(() -> service.crawlFullSchema(validDataSource.getId()))
                        .isInstanceOf(BusinessException.class);

                verify(schemaInfoMapper, times(1)).insert(any(SchemaInfo.class));
            }
        }
    }

    @Nested
    @DisplayName("资源释放测试")
    class ResourceCleanupTests {

        @Test
        @DisplayName("异常发生时 - 数据库连接应正确关闭")
        void crawlSchema_Exception_ShouldCloseConnection() throws Exception {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.getMetaData()).thenThrow(new SQLException("Test exception"));

                assertThatThrownBy(() -> service.crawlSchema(validDataSource.getId()))
                        .isInstanceOf(BusinessException.class);

                verify(mockConn, times(1)).close();
            }
        }

        @Test
        @DisplayName("正常执行完成 - 数据库连接应正确关闭")
        void crawlSchema_Success_ShouldCloseConnection() throws Exception {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);

                var metaData = mock(java.sql.DatabaseMetaData.class);
                when(mockConn.getMetaData()).thenReturn(metaData);
                var tablesRs = mock(java.sql.ResultSet.class);
                when(metaData.getTables(any(), any(), any(), any())).thenReturn(tablesRs);
                when(tablesRs.next()).thenReturn(false);

                SchemaInfo result = service.crawlSchema(validDataSource.getId());

                assertThat(result).isNotNull();
                verify(mockConn, times(1)).close();
            }
        }
    }

    @Nested
    @DisplayName("采样数据降级测试")
    class SampleDataFallbackTests {

        @Test
        @DisplayName("采样数据查询失败 - 应抛出异常")
        void getSampleData_QueryFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.createStatement()).thenThrow(new SQLException("Sample data query timeout"));

                assertThatThrownBy(() -> service.getSampleData(validDataSource.getId(), "users", 100))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to get sample data");

                verify(mockConn, times(1)).close();
            }
        }

        @Test
        @DisplayName("表统计信息查询失败 - 应抛出异常")
        void getTableStatistics_QueryFailed_ShouldThrowException() {
            when(dataSourceMapper.selectById(validDataSource.getId())).thenReturn(validDataSource);

            try (MockedStatic<DriverManager> driverManagerMock = mockStatic(DriverManager.class)) {
                Connection mockConn = mock(Connection.class);
                driverManagerMock.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                        .thenReturn(mockConn);
                when(mockConn.createStatement()).thenThrow(new SQLException("Statistics query timeout"));

                assertThatThrownBy(() -> service.getTableStatistics(validDataSource.getId(), "users"))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Failed to get table statistics");

                verify(mockConn, times(1)).close();
            }
        }
    }

    @Nested
    @DisplayName("服务层CRUD测试")
    class ServiceCrudTests {

        @Test
        @DisplayName("创建SchemaInfo - 应成功")
        void createSchemaInfo_ShouldSucceed() {
            SchemaInfo schemaInfo = new SchemaInfo();
            schemaInfo.setDataSourceId("ds-001");
            schemaInfo.setSchemaName("test");
            when(schemaInfoMapper.insert(any(SchemaInfo.class))).thenReturn(1);

            SchemaInfo result = service.create(schemaInfo);

            assertThat(result).isNotNull();
            verify(schemaInfoMapper, times(1)).insert(schemaInfo);
        }

        @Test
        @DisplayName("查询SchemaInfo - 存在时应返回")
        void findById_Exists_ShouldReturn() {
            SchemaInfo schemaInfo = new SchemaInfo();
            schemaInfo.setId("schema-001");
            when(schemaInfoMapper.selectById("schema-001")).thenReturn(schemaInfo);

            SchemaInfo result = service.findById("schema-001");

            assertThat(result).isNotNull().isEqualTo(schemaInfo);
        }

        @Test
        @DisplayName("查询SchemaInfo - 不存在时应返回null")
        void findById_NotExists_ShouldReturnNull() {
            when(schemaInfoMapper.selectById("non-existent")).thenReturn(null);

            SchemaInfo result = service.findById("non-existent");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("删除SchemaInfo - 应成功")
        void deleteSchemaInfo_ShouldSucceed() {
            doNothing().when(schemaInfoMapper).deleteById("schema-001");

            assertThatCode(() -> service.delete("schema-001"))
                    .doesNotThrowAnyException();

            verify(schemaInfoMapper, times(1)).deleteById("schema-001");
        }

        @Test
        @DisplayName("存在性检查 - 存在时返回true")
        void exists_WhenExists_ShouldReturnTrue() {
            SchemaInfo schemaInfo = new SchemaInfo();
            schemaInfo.setId("schema-001");
            when(schemaInfoMapper.selectById("schema-001")).thenReturn(schemaInfo);

            boolean exists = service.exists("schema-001");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("存在性检查 - 不存在时返回false")
        void exists_WhenNotExists_ShouldReturnFalse() {
            when(schemaInfoMapper.selectById("non-existent")).thenReturn(null);

            boolean exists = service.exists("non-existent");

            assertThat(exists).isFalse();
        }
    }
}
