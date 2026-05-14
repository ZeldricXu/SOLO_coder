package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.entity.Appointment;
import com.medical.appointment.entity.Schedule;
import com.medical.appointment.repository.AppointmentRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("CancellationService 单元测试 - 取消模块（异步名额恢复）")
@ExtendWith(MockitoExtension.class)
class CancellationWithAsyncQuotaTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    
    @Mock
    private AppointmentService appointmentService;
    
    @Mock
    private PatientService patientService;
    
    @Mock
    private DoctorService doctorService;
    
    @Mock
    private ScheduleService scheduleService;
    
    @Mock
    private StatisticsService statisticsService;
    
    @Mock
    private HistoryService historyService;

    @InjectMocks
    private CancellationService cancellationService;
    
    private AsyncQuotaService asyncQuotaService;

    private Appointment appointedAppointment;
    private Appointment visitedAppointment;
    private Appointment cancelledAppointment;
    private Schedule testSchedule;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        
        testSchedule = TestDataBuilder.createTestSchedule("dept_001", "doctor_001", "morning");
        testSchedule.setScheduleAvailable(40);
        testSchedule.setScheduleQuota(50);
        
        appointedAppointment = TestDataBuilder.createTestAppointment(
                "patient_001", testSchedule.getScheduleId(), "doctor_001", "appointed");
        
        visitedAppointment = TestDataBuilder.createTestAppointment(
                "patient_002", testSchedule.getScheduleId(), "doctor_001", "visited");
        
        cancelledAppointment = TestDataBuilder.createTestAppointment(
                "patient_003", testSchedule.getScheduleId(), "doctor_001", "cancelled");
    }

    @AfterEach
    void tearDown() {
        if (asyncQuotaService != null) {
            asyncQuotaService.resetCounters();
        }
    }

    @Nested
    @DisplayName("基本取消功能测试")
    class BasicCancellationTests {

        @Test
        @DisplayName("应该成功取消已预约挂号")
        void shouldCancelAppointedAppointment() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            String result = cancellationService.cancelAppointment(
                    appointedAppointment.getAppointmentId(), "个人原因");

            assertEquals(appointedAppointment.getAppointmentId(), result);
            assertEquals("cancelled", appointedAppointment.getAppointmentStatus());
            assertEquals("个人原因", appointedAppointment.getCancelReason());

            verify(patientService).decrementAppointmentCount("patient_001");
            verify(doctorService).decrementAppointmentCount("doctor_001");
            verify(statisticsService).incrementCancelCount();
        }

        @Test
        @DisplayName("挂号不存在时应该取消失败")
        void shouldFailWhenAppointmentNotFound() {
            when(appointmentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                cancellationService.cancelAppointment("nonexistent", null);
            });

            assertEquals("挂号不存在", exception.getMessage());
        }

        @Test
        @DisplayName("已就诊挂号不应该被取消")
        void shouldNotCancelVisitedAppointment() {
            when(appointmentRepository.findById(visitedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(visitedAppointment));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                cancellationService.cancelAppointment(visitedAppointment.getAppointmentId(), null);
            });

            assertEquals("已就诊的挂号不可取消", exception.getMessage());
        }

        @Test
        @DisplayName("已取消挂号不应该被重复取消")
        void shouldNotCancelAlreadyCancelled() {
            when(appointmentRepository.findById(cancelledAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(cancelledAppointment));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                cancellationService.cancelAppointment(cancelledAppointment.getAppointmentId(), null);
            });

            assertEquals("挂号已取消，请勿重复操作", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("名额恢复测试")
    class QuotaRestoreTests {

        @Test
        @DisplayName("取消后应该恢复排班名额")
        void shouldRestoreQuotaAfterCancellation() {
            int initialAvailable = testSchedule.getScheduleAvailable();

            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenAnswer(inv -> {
                testSchedule.setScheduleAvailable(testSchedule.getScheduleAvailable() + 1);
                return true;
            });

            cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);

            assertEquals(initialAvailable + 1, testSchedule.getScheduleAvailable());
            verify(scheduleService).increaseAvailable(testSchedule.getScheduleId());
        }

        @Test
        @DisplayName("名额恢复失败时应该抛出异常")
        void shouldThrowExceptionWhenQuotaRestoreFails() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(false);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);
            });

            assertEquals("恢复名额失败", exception.getMessage());
        }

        @Test
        @DisplayName("从已满状态恢复名额后应该变为可用")
        void shouldChangeFromFullToAvailableAfterRestore() {
            testSchedule.setScheduleAvailable(0);
            testSchedule.setScheduleStatus("full");

            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenAnswer(inv -> {
                testSchedule.setScheduleAvailable(1);
                testSchedule.setScheduleStatus("available");
                return true;
            });

            cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);

            assertEquals(1, testSchedule.getScheduleAvailable());
            assertEquals("available", testSchedule.getScheduleStatus());
        }
    }

    @Nested
    @DisplayName("异步名额恢复测试")
    class AsyncQuotaRestoreTests {

        @BeforeEach
        void setUpAsync() {
            asyncQuotaService = new AsyncQuotaService(scheduleService);
        }

        @Test
        @DisplayName("异步名额恢复应该立即返回响应不阻塞")
        @Timeout(value = 1, unit = java.util.concurrent.TimeUnit.SECONDS)
        void shouldReturnImmediately() {
            long startTime = System.currentTimeMillis();
            
            String taskId = asyncQuotaService.submitQuotaRestore(
                    testSchedule.getScheduleId(), appointedAppointment.getAppointmentId());

            long elapsed = System.currentTimeMillis() - startTime;
            
            assertNotNull(taskId, "任务ID不应为空");
            assertTrue(taskId.startsWith("QUOTA_TASK_"), "任务ID格式不正确");
            assertTrue(elapsed < 500, "应该在500ms内返回，实际耗时: " + elapsed + "ms");
        }

        @Test
        @DisplayName("后台Worker应该执行名额恢复")
        void workerShouldExecuteQuotaRestore() throws Exception {
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            String taskId = asyncQuotaService.submitQuotaRestore(
                    testSchedule.getScheduleId(), appointedAppointment.getAppointmentId());

            asyncQuotaService.waitForTaskCompletion(taskId, 5000);

            AsyncQuotaService.QuotaTaskResult result = asyncQuotaService.getTaskResult(taskId);
            assertNotNull(result, "任务结果不应为空");
            assertTrue(result.isSuccess(), "任务应该成功");
            assertEquals("名额恢复成功", result.getMessage());
        }

        @Test
        @DisplayName("应该正确统计异步任务")
        void shouldCountAsyncTasksCorrectly() throws Exception {
            when(scheduleService.increaseAvailable(anyString())).thenReturn(true);

            assertEquals(0, asyncQuotaService.getTotalTasks());
            assertEquals(0, asyncQuotaService.getSuccessfulTasks());
            assertEquals(0, asyncQuotaService.getFailedTasks());

            String taskId1 = asyncQuotaService.submitQuotaRestore("schedule_1", "appoint_1");
            String taskId2 = asyncQuotaService.submitQuotaRestore("schedule_2", "appoint_2");

            asyncQuotaService.waitForAllTasks(5000);

            assertEquals(2, asyncQuotaService.getTotalTasks());
            assertEquals(2, asyncQuotaService.getSuccessfulTasks());
            assertEquals(0, asyncQuotaService.getFailedTasks());
        }

        @Test
        @DisplayName("Worker执行失败应该正确记录")
        void workerFailureShouldBeRecorded() throws Exception {
            when(scheduleService.increaseAvailable("schedule_failed")).thenReturn(false);

            String taskId = asyncQuotaService.submitQuotaRestore("schedule_failed", "appoint_failed");

            asyncQuotaService.waitForTaskCompletion(taskId, 5000);

            AsyncQuotaService.QuotaTaskResult result = asyncQuotaService.getTaskResult(taskId);
            assertNotNull(result);
            assertTrue(result.getMessage().contains("失败"), "失败消息应该包含失败标识");
        }

        @Test
        @DisplayName("应该支持配额释放任务类型")
        void shouldSupportQuotaReleaseTask() throws Exception {
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            String taskId = asyncQuotaService.submitQuotaRelease(
                    testSchedule.getScheduleId(), appointedAppointment.getAppointmentId());

            asyncQuotaService.waitForTaskCompletion(taskId, 5000);

            AsyncQuotaService.QuotaTaskResult result = asyncQuotaService.getTaskResult(taskId);
            assertNotNull(result);
            assertTrue(result.isSuccess());
            assertTrue(result.getMessage().contains("释放"), "消息应该包含释放");
        }
    }

    @Nested
    @DisplayName("取消与逾期场景测试")
    class CancellationAndExpiryScenariosTests {

        @Test
        @DisplayName("用户主动取消场景")
        void userInitiatedCancellation() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            String result = cancellationService.cancelAppointment(
                    appointedAppointment.getAppointmentId(), "用户主动取消预约");

            assertEquals("appointed".equals(cancelledAppointment.getAppointmentStatus()) ? 
                    "cancelled" : "cancelled", appointedAppointment.getAppointmentStatus());
            verify(historyService).recordHistory(
                    eq(appointedAppointment.getAppointmentId()),
                    anyString(),
                    anyString(),
                    eq("cancelled"),
                    anyString(),
                    eq("用户主动取消预约"));
        }

        @Test
        @DisplayName("系统逾期取消场景")
        void systemInitiatedExpiry() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            String result = cancellationService.cancelAppointment(
                    appointedAppointment.getAppointmentId(), "系统自动取消：预约超时未就诊");

            assertEquals(appointedAppointment.getAppointmentId(), result);
            assertEquals("cancelled", appointedAppointment.getAppointmentStatus());
            verify(historyService).recordHistory(
                    eq(appointedAppointment.getAppointmentId()),
                    anyString(),
                    anyString(),
                    eq("cancelled"),
                    anyString(),
                    eq("系统自动取消：预约超时未就诊"));
        }

        @Test
        @DisplayName("取消后应该更新统计数据")
        void shouldUpdateStatisticsAfterCancellation() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);

            verify(statisticsService).incrementCancelCount();
        }

        @Test
        @DisplayName("取消后应该记录历史")
        void shouldRecordCancellationHistory() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            cancellationService.cancelAppointment(
                    appointedAppointment.getAppointmentId(), "测试取消原因");

            verify(historyService).recordHistory(
                    eq(appointedAppointment.getAppointmentId()),
                    anyString(),
                    eq("appointed"),
                    eq("cancelled"),
                    anyString(),
                    eq("测试取消原因"));
        }
    }

    @Nested
    @DisplayName("患者和医生计数更新测试")
    class CountUpdateTests {

        @Test
        @DisplayName("取消后应该减少患者挂号计数")
        void shouldDecrementPatientAppointmentCount() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);

            verify(patientService).decrementAppointmentCount("patient_001");
        }

        @Test
        @DisplayName("取消后应该减少医生挂号计数")
        void shouldDecrementDoctorAppointmentCount() {
            when(appointmentRepository.findById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(scheduleService.increaseAvailable(testSchedule.getScheduleId())).thenReturn(true);

            cancellationService.cancelAppointment(appointedAppointment.getAppointmentId(), null);

            verify(doctorService).decrementAppointmentCount("doctor_001");
        }
    }
}
