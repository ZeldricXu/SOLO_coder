package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.entity.Appointment;
import com.medical.appointment.entity.Patient;
import com.medical.appointment.repository.AppointmentRepository;
import com.medical.appointment.repository.PatientRepository;
import com.medical.appointment.service.ReminderService.ReminderRecord;
import com.medical.appointment.service.ReminderService.ReminderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("ReminderService 单元测试 - 就诊提醒机制")
@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    
    @Mock
    private PatientRepository patientRepository;

    @InjectMocks
    private ReminderService reminderService;

    private Patient testPatient;
    private Appointment highFreqAppointment;
    private Appointment lowFreqAppointment;
    private Appointment farFutureAppointment;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        reminderService.clearAllReminderHistory();
        
        testPatient = TestDataBuilder.createTestPatient("normal");
        
        highFreqAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_001", "doctor_001", "appointed");
        highFreqAppointment.setAppointmentTime(LocalDateTime.now().plusHours(12));
        
        lowFreqAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_002", "doctor_002", "appointed");
        lowFreqAppointment.setAppointmentTime(LocalDateTime.now().plusHours(48));
        
        farFutureAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_003", "doctor_003", "appointed");
        farFutureAppointment.setAppointmentTime(LocalDateTime.now().plusDays(10));
    }

    @Nested
    @DisplayName("基本提醒功能测试")
    class BasicReminderTests {

        @Test
        @DisplayName("应该成功发送就诊提醒")
        void shouldSendReminderSuccessfully() {
            when(appointmentRepository.findById(highFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(highFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            ReminderResult result = reminderService.sendReminder(highFreqAppointment.getAppointmentId());

            assertTrue(result.isSent(), "提醒应该发送成功");
            assertEquals(testPatient.getPatientName(), result.getPatientName());
            assertEquals(testPatient.getPatientPhone(), result.getPhone());
            assertTrue(result.getMessage().contains("就诊提醒"), "消息应该包含就诊提醒");
            
            List<ReminderRecord> records = reminderService.getSentReminders(highFreqAppointment.getAppointmentId());
            assertEquals(1, records.size(), "应该有1条提醒记录");
        }

        @Test
        @DisplayName("挂号不存在时应该发送失败")
        void shouldFailWhenAppointmentNotFound() {
            when(appointmentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            ReminderResult result = reminderService.sendReminder("nonexistent");

            assertFalse(result.isSent(), "挂号不存在时提醒应该失败");
            assertEquals("ERROR", result.getReminderType());
            assertEquals("挂号不存在", result.getMessage());
        }

        @Test
        @DisplayName("患者不存在时应该发送失败")
        void shouldFailWhenPatientNotFound() {
            when(appointmentRepository.findById(highFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(highFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId())).thenReturn(Optional.empty());

            ReminderResult result = reminderService.sendReminder(highFreqAppointment.getAppointmentId());

            assertFalse(result.isSent(), "患者不存在时提醒应该失败");
            assertEquals("ERROR", result.getReminderType());
            assertEquals("患者不存在", result.getMessage());
        }
    }

    @Nested
    @DisplayName("提醒频率差异测试")
    class ReminderFrequencyTests {

        @Test
        @DisplayName("临近预约应该使用高频提醒")
        void shouldUseHighFrequencyForNearAppointment() {
            when(appointmentRepository.findById(highFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(highFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            ReminderResult result = reminderService.sendReminder(highFreqAppointment.getAppointmentId());

            assertTrue(result.isSent());
            assertEquals("HIGH_FREQUENCY", result.getReminderType());
            assertTrue(result.getMessage().contains("24小时内"), "高频提醒消息应该包含24小时内");
        }

        @Test
        @DisplayName("较远预约应该使用低频提醒")
        void shouldUseLowFrequencyForDistantAppointment() {
            when(appointmentRepository.findById(lowFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(lowFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            ReminderResult result = reminderService.sendReminder(lowFreqAppointment.getAppointmentId());

            assertTrue(result.isSent());
            assertEquals("LOW_FREQUENCY", result.getReminderType());
            assertTrue(result.getMessage().contains("提前做好准备"), "低频提醒消息应该包含提前准备");
        }

        @Test
        @DisplayName("高频提醒间隔应该更短")
        void highFrequencyShouldHaveShorterInterval() {
            int highFreqInterval = reminderService.getReminderIntervalHours("HIGH_FREQUENCY");
            int lowFreqInterval = reminderService.getReminderIntervalHours("LOW_FREQUENCY");

            assertEquals(2, highFreqInterval, "高频提醒间隔应为2小时");
            assertEquals(24, lowFreqInterval, "低频提醒间隔应为24小时");
            assertTrue(highFreqInterval < lowFreqInterval, "高频间隔应该更短");
        }

        @Test
        @DisplayName("高频阈值应该小于低频阈值")
        void highFrequencyThresholdShouldBeSmaller() {
            long highFreqThreshold = reminderService.getHighFrequencyThresholdHours();
            long lowFreqThreshold = reminderService.getLowFrequencyThresholdHours();

            assertEquals(24, highFreqThreshold, "高频阈值应为24小时");
            assertEquals(72, lowFreqThreshold, "低频阈值应为72小时");
            assertTrue(highFreqThreshold < lowFreqThreshold, "高频阈值应该更小");
        }
    }

    @Nested
    @DisplayName("提醒发送频率控制测试")
    class ReminderRateLimitTests {

        @Test
        @DisplayName("高频提醒在间隔内应该被限制")
        void shouldThrottleHighFrequencyReminders() {
            when(appointmentRepository.findById(highFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(highFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            ReminderResult result1 = reminderService.sendReminder(highFreqAppointment.getAppointmentId());
            assertTrue(result1.isSent(), "第一次提醒应该成功");

            ReminderResult result2 = reminderService.sendReminder(highFreqAppointment.getAppointmentId());
            assertFalse(result2.isSent(), "间隔内第二次提醒应该被限制");
            assertEquals("HIGH_FREQUENCY", result2.getReminderType());

            int count = reminderService.getReminderCount(highFreqAppointment.getAppointmentId());
            assertEquals(1, count, "应该只有1条提醒记录");
        }

        @Test
        @DisplayName("不同类型的提醒应该独立计数")
        void differentTypesShouldBeCountedSeparately() {
            Appointment testAppointment = TestDataBuilder.createTestAppointment(
                    testPatient.getPatientId(), "schedule_001", "doctor_001", "appointed");
            testAppointment.setAppointmentTime(LocalDateTime.now().plusHours(60));

            when(appointmentRepository.findById(testAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(testAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            ReminderResult result1 = reminderService.sendReminder(testAppointment.getAppointmentId());
            assertTrue(result1.isSent());

            testAppointment.setAppointmentTime(LocalDateTime.now().plusHours(12));
            ReminderResult result2 = reminderService.sendReminder(testAppointment.getAppointmentId());
            assertTrue(result2.isSent(), "不同类型的提醒应该可以发送");

            int count = reminderService.getReminderCount(testAppointment.getAppointmentId());
            assertEquals(2, count, "应该有2条不同类型的提醒记录");
        }
    }

    @Nested
    @DisplayName("批量提醒检查测试")
    class BatchReminderCheckTests {

        @Test
        @DisplayName("应该只对72小时内的预约发送提醒")
        void shouldOnlyRemindWithin72Hours() {
            when(appointmentRepository.findByAppointmentStatus("appointed"))
                    .thenReturn(Arrays.asList(highFreqAppointment, lowFreqAppointment, farFutureAppointment));
            when(appointmentRepository.findById(anyString()))
                    .thenReturn(Optional.of(highFreqAppointment))
                    .thenReturn(Optional.of(lowFreqAppointment))
                    .thenReturn(Optional.of(farFutureAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            List<ReminderResult> results = reminderService.checkAndSendReminders(LocalDateTime.now());

            assertEquals(2, results.size(), "应该只发送2个提醒（72小时内的）");
        }
    }

    @Nested
    @DisplayName("提醒记录管理测试")
    class ReminderRecordManagementTests {

        @Test
        @DisplayName("应该正确清除提醒历史")
        void shouldClearReminderHistory() {
            when(appointmentRepository.findById(highFreqAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(highFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            reminderService.sendReminder(highFreqAppointment.getAppointmentId());
            assertTrue(reminderService.getReminderCount(highFreqAppointment.getAppointmentId()) > 0);

            reminderService.clearReminderHistory(highFreqAppointment.getAppointmentId());
            assertEquals(0, reminderService.getReminderCount(highFreqAppointment.getAppointmentId()));
        }

        @Test
        @DisplayName("应该正确清除所有提醒历史")
        void shouldClearAllReminderHistory() {
            when(appointmentRepository.findById(anyString()))
                    .thenReturn(Optional.of(highFreqAppointment))
                    .thenReturn(Optional.of(lowFreqAppointment));
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));

            reminderService.sendReminder(highFreqAppointment.getAppointmentId());
            reminderService.sendReminder(lowFreqAppointment.getAppointmentId());

            reminderService.clearAllReminderHistory();

            assertEquals(0, reminderService.getReminderCount(highFreqAppointment.getAppointmentId()));
            assertEquals(0, reminderService.getReminderCount(lowFreqAppointment.getAppointmentId()));
        }
    }
}
