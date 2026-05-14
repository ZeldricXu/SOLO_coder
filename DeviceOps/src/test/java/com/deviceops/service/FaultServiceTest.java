package com.deviceops.service;

import com.deviceops.builder.TestDataBuilder;
import com.deviceops.dto.FaultReportRequest;
import com.deviceops.entity.FaultRecord;
import com.deviceops.entity.OperationTask;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.FaultRecordRepository;
import com.deviceops.service.analysis.AnalysisService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.fault.FaultService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.service.task.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("故障模块测试")
class FaultServiceTest {

    @Mock
    private FaultRecordRepository faultRecordRepository;

    @Mock
    private DeviceService deviceService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private TaskService taskService;

    @InjectMocks
    private FaultService faultService;

    @Nested
    @DisplayName("故障上报测试")
    class FaultReportTests {

        @Test
        @DisplayName("故障上报成功 - 创建故障记录")
        void reportFault_Success() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord mockFault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            FaultRecord result = faultService.reportFault(request);

            assertNotNull(result);
            assertEquals("fault_001", result.getFaultId());
            assertEquals("pending", result.getFaultStatus());
            verify(faultRecordRepository, times(1)).save(any(FaultRecord.class));
        }

        @Test
        @DisplayName("故障上报成功 - 验证故障基本信息")
        void reportFault_VerifyBasicInfo() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest(
                    "device_001", "hardware", "CPU温度过高"
            );
            FaultRecord mockFault = TestDataBuilder.buildFaultRecord(
                    "fault_001", "device_001", "hardware", "high", "CPU温度过高"
            );

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("device_001", result.getDeviceId());
            assertEquals("hardware", result.getFaultType());
            assertEquals("CPU温度过高", result.getFaultDesc());
            assertEquals("high", result.getFaultLevel());
        }

        @Test
        @DisplayName("故障上报 - 设备不存在时抛出异常")
        void reportFault_DeviceNotExists_ThrowsException() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            when(deviceService.exists("device_001")).thenReturn(false);

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                faultService.reportFault(request);
            });

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("device_001"));
        }

        @Test
        @DisplayName("故障上报 - 设备状态更新为异常")
        void reportFault_UpdatesDeviceStatus() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord mockFault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            faultService.reportFault(request);

            verify(deviceService, times(1)).updateDeviceStatus("device_001", "abnormal");
        }

        @Test
        @DisplayName("故障上报 - 自动创建运维任务")
        void reportFault_CreatesTask() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord mockFault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            faultService.reportFault(request);

            verify(taskService, times(1)).createTaskFromFault(any(FaultRecord.class));
        }

        @Test
        @DisplayName("故障上报 - 记录历史")
        void reportFault_RecordsHistory() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord mockFault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            faultService.reportFault(request);

            verify(historyService, times(1)).recordFaultReport(
                    eq("device_001"), anyString(), eq("CPU温度过高"));
        }

        @Test
        @DisplayName("故障上报 - 更新统计")
        void reportFault_UpdatesStatistics() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord mockFault = TestDataBuilder.buildPendingFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            faultService.reportFault(request);

            verify(analysisService, times(1)).incrementFaultCount();
        }
    }

    @Nested
    @DisplayName("故障优先级处理测试")
    class FaultPriorityTests {

        @Test
        @DisplayName("高优先级故障 - 级别为high")
        void reportFault_HighPriority_LevelIsHigh() {
            FaultReportRequest request = TestDataBuilder.buildHighPriorityFaultRequest();
            FaultRecord mockFault = TestDataBuilder.buildHighPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildHighPriorityTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("high", result.getFaultLevel());
        }

        @Test
        @DisplayName("中优先级故障 - 级别为medium")
        void reportFault_MediumPriority_LevelIsMedium() {
            FaultReportRequest request = TestDataBuilder.buildMediumPriorityFaultRequest();
            FaultRecord mockFault = TestDataBuilder.buildMediumPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildMediumPriorityTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("medium", result.getFaultLevel());
        }

        @Test
        @DisplayName("未指定级别时 - 默认为medium")
        void reportFault_NoLevelSpecified_DefaultsToMedium() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            request.setFaultLevel(null);
            FaultRecord mockFault = TestDataBuilder.buildMediumPriorityFault();

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildMediumPriorityTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("medium", result.getFaultLevel());
        }

        @Test
        @DisplayName("硬件故障 - 类型归一化为hardware")
        void reportFault_HardwareType_Normalized() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest(
                    "device_001", "hardware", "CPU故障"
            );
            FaultRecord mockFault = TestDataBuilder.buildFaultRecord(
                    "fault_001", "device_001", "hardware", "high", "CPU故障"
            );

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("hardware", result.getFaultType());
        }

        @Test
        @DisplayName("软件故障 - 类型归一化为software")
        void reportFault_SoftwareType_Normalized() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest(
                    "device_001", "software", "系统崩溃"
            );
            FaultRecord mockFault = TestDataBuilder.buildFaultRecord(
                    "fault_001", "device_001", "software", "medium", "系统崩溃"
            );

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenReturn(mockFault);
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());

            FaultRecord result = faultService.reportFault(request);

            assertEquals("software", result.getFaultType());
        }
    }

    @Nested
    @DisplayName("故障处理流程测试")
    class FaultProcessingTests {

        @Test
        @DisplayName("处理故障 - 状态转为processing")
        void processFault_StatusProcessing() {
            FaultRecord pendingFault = TestDataBuilder.buildPendingFault();
            when(faultRecordRepository.findById("fault_001")).thenReturn(Optional.of(pendingFault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FaultRecord result = faultService.processFault("fault_001");

            assertEquals("processing", result.getFaultStatus());
        }

        @Test
        @DisplayName("修复故障 - 状态转为resolved")
        void resolveFault_StatusResolved() {
            FaultRecord processingFault = TestDataBuilder.buildProcessingFault();
            when(faultRecordRepository.findById("fault_002")).thenReturn(Optional.of(processingFault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FaultRecord result = faultService.resolveFault("fault_002", "operator_001");

            assertEquals("resolved", result.getFaultStatus());
            assertNotNull(result.getRepairedAt());
        }

        @Test
        @DisplayName("修复故障 - 设备状态恢复正常")
        void resolveFault_UpdatesDeviceStatus() {
            FaultRecord processingFault = TestDataBuilder.buildProcessingFault();
            when(faultRecordRepository.findById("fault_002")).thenReturn(Optional.of(processingFault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            faultService.resolveFault("fault_002", "operator_001");

            verify(deviceService, times(1)).updateDeviceStatus("device_001", "normal");
        }

        @Test
        @DisplayName("修复故障 - 记录历史")
        void resolveFault_RecordsHistory() {
            FaultRecord processingFault = TestDataBuilder.buildProcessingFault();
            when(faultRecordRepository.findById("fault_002")).thenReturn(Optional.of(processingFault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            faultService.resolveFault("fault_002", "operator_001");

            verify(historyService, times(1)).recordFaultRepair(
                    "device_001", "fault_002", "operator_001");
        }

        @Test
        @DisplayName("更新故障状态")
        void updateFaultStatus_Success() {
            FaultRecord fault = TestDataBuilder.buildPendingFault();
            when(faultRecordRepository.findById("fault_001")).thenReturn(Optional.of(fault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            FaultRecord result = faultService.updateFaultStatus("fault_001", "processing");

            assertEquals("processing", result.getFaultStatus());
        }
    }

    @Nested
    @DisplayName("故障查询测试")
    class FaultQueryTests {

        @Test
        @DisplayName("查询故障成功")
        void getFault_Success() {
            FaultRecord fault = TestDataBuilder.buildPendingFault();
            when(faultRecordRepository.findById("fault_001")).thenReturn(Optional.of(fault));

            FaultRecord result = faultService.getFault("fault_001");

            assertNotNull(result);
            assertEquals("fault_001", result.getFaultId());
        }

        @Test
        @DisplayName("查询故障不存在时抛出异常")
        void getFault_NotFound_ThrowsException() {
            when(faultRecordRepository.findById("fault_999")).thenReturn(Optional.empty());

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                faultService.getFault("fault_999");
            });

            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("查询所有故障")
        void getAllFaults_ReturnsList() {
            List<FaultRecord> faults = new ArrayList<>();
            faults.add(TestDataBuilder.buildPendingFault());
            faults.add(TestDataBuilder.buildProcessingFault());
            when(faultRecordRepository.findAll()).thenReturn(faults);

            List<FaultRecord> result = faultService.getAllFaults();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按设备查询故障")
        void getFaultsByDevice_ReturnsRecords() {
            when(deviceService.exists("device_001")).thenReturn(true);
            List<FaultRecord> faults = new ArrayList<>();
            faults.add(TestDataBuilder.buildPendingFault());
            when(faultRecordRepository.findByDeviceIdOrderByReportedAtDesc("device_001"))
                    .thenReturn(faults);

            List<FaultRecord> result = faultService.getFaultsByDevice("device_001");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("按状态查询故障")
        void getFaultsByStatus_ReturnsMatching() {
            List<FaultRecord> pendingFaults = new ArrayList<>();
            pendingFaults.add(TestDataBuilder.buildPendingFault());
            when(faultRecordRepository.findByFaultStatus("pending")).thenReturn(pendingFaults);

            List<FaultRecord> result = faultService.getFaultsByStatus("pending");

            assertEquals(1, result.size());
            assertEquals("pending", result.get(0).getFaultStatus());
        }

        @Test
        @DisplayName("统计待处理故障数量")
        void countByStatus_PendingFaults() {
            when(faultRecordRepository.countByFaultStatus("pending")).thenReturn(15L);

            long count = faultService.countByStatus("pending");

            assertEquals(15L, count);
        }

        @Test
        @DisplayName("统计已修复故障数量")
        void countByStatus_ResolvedFaults() {
            when(faultRecordRepository.countByFaultStatus("resolved")).thenReturn(100L);

            long count = faultService.countByStatus("resolved");

            assertEquals(100L, count);
        }

        @Test
        @DisplayName("统计总故障数量")
        void count_TotalFaults() {
            when(faultRecordRepository.count()).thenReturn(200L);

            long count = faultService.count();

            assertEquals(200L, count);
        }
    }

    @Nested
    @DisplayName("故障异步处理测试")
    class FaultAsyncProcessingTests {

        @Test
        @DisplayName("异步处理故障 - CompletableFuture成功完成")
        void processFaultAsync_CompletableFutureSuccess() throws ExecutionException, InterruptedException {
            FaultRecord pendingFault = TestDataBuilder.buildPendingFault();
            when(faultRecordRepository.findById("fault_001")).thenReturn(Optional.of(pendingFault));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CompletableFuture<FaultRecord> future = CompletableFuture.supplyAsync(() -> 
                faultService.processFault("fault_001")
            );

            FaultRecord result = future.get();
            
            assertNotNull(result);
            assertEquals("processing", result.getFaultStatus());
        }

        @Test
        @DisplayName("多个故障异步处理 - 并发执行")
        void processMultipleFaultsAsync_Concurrent() {
            FaultRecord fault1 = TestDataBuilder.buildPendingFault();
            fault1.setFaultId("fault_001");
            FaultRecord fault2 = TestDataBuilder.buildPendingFault();
            fault2.setFaultId("fault_002");

            when(faultRecordRepository.findById("fault_001")).thenReturn(Optional.of(fault1));
            when(faultRecordRepository.findById("fault_002")).thenReturn(Optional.of(fault2));
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CompletableFuture<FaultRecord> future1 = CompletableFuture.supplyAsync(() -> 
                faultService.processFault("fault_001")
            );
            CompletableFuture<FaultRecord> future2 = CompletableFuture.supplyAsync(() -> 
                faultService.processFault("fault_002")
            );

            CompletableFuture.allOf(future1, future2).join();

            assertDoesNotThrow(() -> {
                assertEquals("processing", future1.get().getFaultStatus());
                assertEquals("processing", future2.get().getFaultStatus());
            });
        }

        @Test
        @DisplayName("故障处理链 - 上报->处理->修复完整流程")
        void faultProcessingChain_CompleteFlow() {
            FaultReportRequest request = TestDataBuilder.buildFaultReportRequest();
            FaultRecord pendingFault = TestDataBuilder.buildPendingFault();
            FaultRecord processingFault = TestDataBuilder.buildProcessingFault();
            processingFault.setFaultId("fault_001");
            FaultRecord resolvedFault = TestDataBuilder.buildResolvedFault();
            resolvedFault.setFaultId("fault_001");

            when(deviceService.exists("device_001")).thenReturn(true);
            when(faultRecordRepository.save(any(FaultRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(taskService.createTaskFromFault(any(FaultRecord.class)))
                    .thenReturn(TestDataBuilder.buildPendingTask());
            
            when(faultRecordRepository.findById("fault_001"))
                    .thenReturn(Optional.of(pendingFault))
                    .thenReturn(Optional.of(processingFault));

            FaultRecord reported = faultService.reportFault(request);
            assertEquals("pending", reported.getFaultStatus());

            FaultRecord processed = faultService.processFault("fault_001");
            assertEquals("processing", processed.getFaultStatus());

            FaultRecord resolved = faultService.resolveFault("fault_001", "operator_001");
            assertEquals("resolved", resolved.getFaultStatus());

            verify(faultRecordRepository, times(4)).save(any(FaultRecord.class));
        }
    }
}
