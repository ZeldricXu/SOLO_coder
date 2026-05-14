package com.healthtrack.service;

import com.healthtrack.dto.HealthDataReportRequest;
import com.healthtrack.dto.HealthDataReportResponse;
import com.healthtrack.entity.HealthData;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.testbuilder.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据采集模块单元测试")
class DataCollectionServiceTest {

    @Mock
    private HealthDataRepository healthDataRepository;

    @Mock
    private AsyncDataProcessingService asyncDataProcessingService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private DataCollectionService dataCollectionService;

    @BeforeEach
    void setUp() {
        dataCollectionService.setUseAsyncProcessing(false);
    }

    @Nested
    @DisplayName("数据质量校验测试")
    class DataQualityValidationTests {

        @Test
        @DisplayName("正常心率数据 - 质量为good")
        void testNormalHeartRateQuality() {
            String quality = dataCollectionService.validateDataQuality("heart_rate", 75.0);
            assertEquals("good", quality);
        }

        @Test
        @DisplayName("异常高心率数据 - 质量为abnormal")
        void testAbnormalHighHeartRateQuality() {
            String quality = dataCollectionService.validateDataQuality("heart_rate", 250.0);
            assertEquals("abnormal", quality);
        }

        @Test
        @DisplayName("异常低心率数据 - 质量为abnormal")
        void testAbnormalLowHeartRateQuality() {
            String quality = dataCollectionService.validateDataQuality("heart_rate", 20.0);
            assertEquals("abnormal", quality);
        }

        @Test
        @DisplayName("正常体重数据 - 质量为good")
        void testNormalWeightQuality() {
            String quality = dataCollectionService.validateDataQuality("weight", 65.0);
            assertEquals("good", quality);
        }

        @Test
        @DisplayName("异常高体重数据 - 质量为abnormal")
        void testAbnormalHighWeightQuality() {
            String quality = dataCollectionService.validateDataQuality("weight", 350.0);
            assertEquals("abnormal", quality);
        }

        @Test
        @DisplayName("正常收缩压 - 质量为good")
        void testNormalBloodPressureQuality() {
            String quality = dataCollectionService.validateDataQuality("blood_pressure_systolic", 120.0);
            assertEquals("good", quality);
        }

        @Test
        @DisplayName("异常体温 - 质量为abnormal")
        void testAbnormalTemperatureQuality() {
            String quality = dataCollectionService.validateDataQuality("temperature", 45.0);
            assertEquals("abnormal", quality);
        }
    }

    @Nested
    @DisplayName("请求参数校验测试")
    class RequestValidationTests {

        @Test
        @DisplayName("用户ID为空 - 抛出异常")
        void testEmptyUserIdThrowsException() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("");
            request.setDataType("heart_rate");
            request.setDataValue(75.0);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
            assertEquals("用户ID不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("数据类型为空 - 抛出异常")
        void testNullDataTypeThrowsException() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("user_001");
            request.setDataType(null);
            request.setDataValue(75.0);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
            assertEquals("数据类型不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("数据值为空 - 抛出异常")
        void testNullDataValueThrowsException() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("user_001");
            request.setDataType("heart_rate");
            request.setDataValue(null);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
            assertEquals("数据值不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("无效数据请求 - 错误处理测试")
        void testInvalidRequestErrorHandling() {
            HealthDataReportRequest request = TestDataBuilder.buildInvalidDataRequest();
            assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
        }
    }

    @Nested
    @DisplayName("数据单位测试")
    class DataUnitTests {

        @Test
        @DisplayName("心率默认单位为bpm")
        void testHeartRateDefaultUnit() {
            String unit = dataCollectionService.getDefaultUnit("heart_rate");
            assertEquals("bpm", unit);
        }

        @Test
        @DisplayName("体重默认单位为kg")
        void testWeightDefaultUnit() {
            String unit = dataCollectionService.getDefaultUnit("weight");
            assertEquals("kg", unit);
        }

        @Test
        @DisplayName("收缩压默认单位为mmHg")
        void testBloodPressureDefaultUnit() {
            String unit = dataCollectionService.getDefaultUnit("blood_pressure_systolic");
            assertEquals("mmHg", unit);
        }

        @Test
        @DisplayName("步数默认单位为steps")
        void testStepsDefaultUnit() {
            String unit = dataCollectionService.getDefaultUnit("steps");
            assertEquals("steps", unit);
        }

        @Test
        @DisplayName("未知数据类型默认单位为空")
        void testUnknownTypeDefaultUnit() {
            String unit = dataCollectionService.getDefaultUnit("unknown_type");
            assertEquals("", unit);
        }
    }

    @Nested
    @DisplayName("数据记录创建测试")
    class DataRecordCreationTests {

        @Test
        @DisplayName("创建健康数据记录 - 字段正确设置")
        void testCreateHealthDataRecord() {
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            HealthData data = dataCollectionService.createHealthDataRecord(request, "good");

            assertNotNull(data.getDataId());
            assertTrue(data.getDataId().startsWith("data_"));
            assertEquals(request.getUserId(), data.getUserId());
            assertEquals(request.getDataType(), data.getDataType());
            assertEquals(request.getDataValue(), data.getDataValue());
            assertEquals("bpm", data.getDataUnit());
            assertEquals("good", data.getQuality());
            assertNotNull(data.getCollectedAt());
        }

        @Test
        @DisplayName("创建数据记录 - ID唯一性测试")
        void testUniqueDataIdGeneration() {
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            
            HealthData data1 = dataCollectionService.createHealthDataRecord(request, "good");
            HealthData data2 = dataCollectionService.createHealthDataRecord(request, "good");
            
            assertNotEquals(data1.getDataId(), data2.getDataId());
        }

        @Test
        @DisplayName("异常数据记录创建")
        void testAbnormalDataRecordCreation() {
            HealthDataReportRequest request = TestDataBuilder.buildAbnormalHeartRateRequest();
            HealthData data = dataCollectionService.createHealthDataRecord(request, "abnormal");
            
            assertEquals("abnormal", data.getQuality());
            assertNotNull(data.getDataId());
        }
    }

    @Nested
    @DisplayName("同步数据上报测试")
    class SyncReportingTests {

        @Test
        @DisplayName("正常数据上报 - 成功返回响应")
        void testNormalDataReportSync() {
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            HealthData mockData = TestDataBuilder.buildNormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
            assertNotNull(response.getDataId());
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
            verify(historyService, times(1)).recordHistory(
                    eq(request.getUserId()),
                    eq(request.getDataType()),
                    eq("DATA_COLLECTED"),
                    any(),
                    eq(request.getDataValue()),
                    anyString()
            );
        }

        @Test
        @DisplayName("异常数据上报 - 正常处理流程")
        void testAbnormalDataReportSync() {
            HealthDataReportRequest request = TestDataBuilder.buildAbnormalHeartRateRequest();
            HealthData mockData = TestDataBuilder.buildAbnormalHealthData();
            
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(mockData);
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
        }

        @Test
        @DisplayName("数据存储失败 - 异常传播")
        void testDataSaveFailure() {
            HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
            
            when(healthDataRepository.save(any(HealthData.class))).thenThrow(new RuntimeException("数据库连接失败"));
            
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> dataCollectionService.reportHealthData(request));
            assertEquals("数据库连接失败", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("高并发数据上报测试")
    class HighConcurrencyTests {

        @Test
        @DisplayName("100并发请求 - 队列处理能力测试")
        void testHighConcurrencyReporting() throws InterruptedException {
            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            when(healthDataRepository.save(any(HealthData.class))).thenAnswer((Answer<HealthData>) invocation -> {
                HealthData data = invocation.getArgument(0);
                return data;
            });

            List<Future<HealthDataReportResponse>> futures = new ArrayList<>();
            
            for (int i = 0; i < threadCount; i++) {
                final String userId = "user_" + (i % 10);
                Future<HealthDataReportResponse> future = executor.submit(() -> {
                    try {
                        HealthDataReportRequest request = TestDataBuilder.buildRequestWithUserId(userId);
                        HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
                        if (response != null && response.getDataId() != null) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                        return response;
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        throw e;
                    } finally {
                        latch.countDown();
                    }
                });
                futures.add(future);
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertEquals(threadCount, successCount.get());
            assertEquals(0, failCount.get());
            verify(healthDataRepository, times(threadCount)).save(any(HealthData.class));
        }

        @Test
        @DisplayName("并发请求 - ID唯一性保证")
        void testConcurrentUniqueIdGeneration() throws InterruptedException {
            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(threadCount);
            ConcurrentHashMap<String, Boolean> idMap = new ConcurrentHashMap<>();
            AtomicInteger duplicateCount = new AtomicInteger(0);

            when(healthDataRepository.save(any(HealthData.class))).thenAnswer((Answer<HealthData>) invocation -> {
                HealthData data = invocation.getArgument(0);
                if (idMap.putIfAbsent(data.getDataId(), true) != null) {
                    duplicateCount.incrementAndGet();
                }
                return data;
            });

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        HealthDataReportRequest request = TestDataBuilder.buildNormalHeartRateRequest();
                        dataCollectionService.reportHealthData(request);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(0, duplicateCount.get());
            assertEquals(threadCount, idMap.size());
        }
    }

    @Nested
    @DisplayName("数据格式错误容错测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("空用户ID - 立即抛出异常")
        void testEmptyUserIdErrorHandling() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("");
            request.setDataType("heart_rate");
            request.setDataValue(75.0);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
            assertEquals("用户ID不能为空", exception.getMessage());
            verify(healthDataRepository, never()).save(any());
        }

        @Test
        @DisplayName("空白用户ID - 立即抛出异常")
        void testBlankUserIdErrorHandling() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("   ");
            request.setDataType("heart_rate");
            request.setDataValue(75.0);

            assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
        }

        @Test
        @DisplayName("空数据类型 - 立即抛出异常")
        void testEmptyDataTypeErrorHandling() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("user_001");
            request.setDataType("");
            request.setDataValue(75.0);

            assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
        }

        @Test
        @DisplayName("所有参数无效 - 第一次验证失败")
        void testAllInvalidParams() {
            HealthDataReportRequest request = new HealthDataReportRequest();
            request.setUserId("");
            request.setDataType("");
            request.setDataValue(null);

            assertThrows(IllegalArgumentException.class,
                    () -> dataCollectionService.reportHealthData(request));
        }
    }

    @Nested
    @DisplayName("不同数据类型测试")
    class DataTypeTests {

        @Test
        @DisplayName("心率数据上报")
        void testHeartRateReporting() {
            HealthDataReportRequest request = TestDataBuilder.buildRequestWithType("heart_rate", 75.0);
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(TestDataBuilder.buildNormalHealthData());
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
            verify(healthDataRepository, times(1)).save(any(HealthData.class));
        }

        @Test
        @DisplayName("体重数据上报")
        void testWeightReporting() {
            HealthDataReportRequest request = TestDataBuilder.buildRequestWithType("weight", 65.0);
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(TestDataBuilder.buildNormalHealthData());
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
        }

        @Test
        @DisplayName("步数数据上报")
        void testStepsReporting() {
            HealthDataReportRequest request = TestDataBuilder.buildRequestWithType("steps", 8000.0);
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(TestDataBuilder.buildNormalHealthData());
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
        }

        @Test
        @DisplayName("睡眠数据上报")
        void testSleepReporting() {
            HealthDataReportRequest request = TestDataBuilder.buildRequestWithType("sleep_hours", 7.5);
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(TestDataBuilder.buildNormalHealthData());
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
        }

        @Test
        @DisplayName("体温数据上报")
        void testTemperatureReporting() {
            HealthDataReportRequest request = TestDataBuilder.buildRequestWithType("temperature", 36.5);
            when(healthDataRepository.save(any(HealthData.class))).thenReturn(TestDataBuilder.buildNormalHealthData());
            
            HealthDataReportResponse response = dataCollectionService.reportHealthData(request);
            
            assertNotNull(response);
        }
    }
}
