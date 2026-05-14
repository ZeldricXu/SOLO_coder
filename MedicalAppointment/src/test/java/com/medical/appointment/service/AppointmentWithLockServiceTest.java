package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.dto.AppointmentResult;
import com.medical.appointment.entity.*;
import com.medical.appointment.repository.*;
import com.medical.appointment.service.LockService.LockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AppointmentService 单元测试 - 挂号模块")
@ExtendWith(MockitoExtension.class)
class AppointmentWithLockServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    
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
    
    @Mock
    private HospitalRepository hospitalRepository;
    
    @Mock
    private DepartmentRepository departmentRepository;
    
    @Mock
    private DoctorRepository doctorRepository;
    
    @Mock
    private PatientRepository patientRepository;
    
    @Spy
    private LockService lockService;

    @InjectMocks
    private AppointmentService appointmentService;

    private Patient testPatient;
    private Doctor testDoctor;
    private Department testDepartment;
    private Hospital testHospital;
    private Schedule testSchedule;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        
        testPatient = TestDataBuilder.createTestPatient("normal");
        testDoctor = TestDataBuilder.createTestDoctor("dept_001");
        testDepartment = TestDataBuilder.createTestDepartment("hospital_001", "internal");
        testHospital = TestDataBuilder.createTestHospital();
        testSchedule = TestDataBuilder.createTestSchedule(testDepartment.getDepartmentId(), testDoctor.getDoctorId(), "morning");
    }

    @Nested
    @DisplayName("名额锁定与挂号流程测试")
    class LockAndAppointmentFlowTests {

        @Test
        @DisplayName("应该成功创建挂号预约并锁定名额")
        void shouldCreateAppointmentSuccessfully() {
            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));
            when(departmentRepository.findById(testSchedule.getDepartmentId())).thenReturn(Optional.of(testDepartment));
            when(hospitalRepository.findById(testDepartment.getHospitalId())).thenReturn(Optional.of(testHospital));
            when(scheduleService.decreaseAvailable(testSchedule.getScheduleId())).thenReturn(true);
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(doctorRepository.findById(testDoctor.getDoctorId())).thenReturn(Optional.of(testDoctor));

            AppointmentResult result = appointmentService.createAppointment(
                    testPatient.getPatientId(), testSchedule.getScheduleId());

            assertNotNull(result, "挂号结果不应为空");
            assertEquals(testPatient.getPatientName(), result.getPatientName());
            assertEquals(testDoctor.getDoctorName(), result.getDoctorName());
            assertEquals("appointed", result.getAppointmentStatus());

            verify(scheduleService).decreaseAvailable(testSchedule.getScheduleId());
            verify(patientService).incrementAppointmentCount(testPatient.getPatientId());
            verify(doctorService).incrementAppointmentCount(testDoctor.getDoctorId());
            verify(appointmentRepository).save(any(Appointment.class));
        }

        @Test
        @DisplayName("排班名额已满时应该拒绝挂号")
        void shouldRejectWhenQuotaIsFull() {
            testSchedule.setScheduleAvailable(0);
            testSchedule.setScheduleStatus("full");

            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                appointmentService.createAppointment(testPatient.getPatientId(), testSchedule.getScheduleId());
            });

            assertTrue(exception.getMessage().contains("排班已满") || exception.getMessage().contains("关闭"),
                    "应该拒绝已满排班的挂号请求");
            verify(scheduleService, never()).decreaseAvailable(anyString());
            verify(appointmentRepository, never()).save(any(Appointment.class));
        }

        @Test
        @DisplayName("扣减名额失败时应该抛出异常")
        void shouldThrowExceptionWhenQuotaDecreaseFails() {
            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));
            when(departmentRepository.findById(testSchedule.getDepartmentId())).thenReturn(Optional.of(testDepartment));
            when(hospitalRepository.findById(testDepartment.getHospitalId())).thenReturn(Optional.of(testHospital));
            when(scheduleService.decreaseAvailable(testSchedule.getScheduleId())).thenReturn(false);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                appointmentService.createAppointment(testPatient.getPatientId(), testSchedule.getScheduleId());
            });

            assertTrue(exception.getMessage().contains("扣减名额失败"), "应该抛出名额扣减失败异常");
            verify(appointmentRepository, never()).save(any(Appointment.class));
        }
    }

    @Nested
    @DisplayName("并发挂号与名额恢复测试")
    class ConcurrentAndQuotaRestoreTests {

        @Test
        @DisplayName("患者不存在时应该拒绝挂号")
        void shouldRejectWhenPatientNotFound() {
            when(patientService.getPatientById("nonexistent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                appointmentService.createAppointment("nonexistent", testSchedule.getScheduleId());
            });

            assertEquals("患者不存在", exception.getMessage());
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("患者状态不可用时应该拒绝挂号")
        void shouldRejectWhenPatientInactive() {
            Patient frozenPatient = TestDataBuilder.createTestPatient("frozen");

            when(patientService.getPatientById(frozenPatient.getPatientId())).thenReturn(Optional.of(frozenPatient));
            when(patientService.isPatientActive(frozenPatient.getPatientId())).thenReturn(false);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                appointmentService.createAppointment(frozenPatient.getPatientId(), testSchedule.getScheduleId());
            });

            assertEquals("患者状态不可用", exception.getMessage());
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("排班不存在时应该拒绝挂号")
        void shouldRejectWhenScheduleNotFound() {
            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById("nonexistent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                appointmentService.createAppointment(testPatient.getPatientId(), "nonexistent");
            });

            assertEquals("排班不存在", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("名额扣减与恢复正确性测试")
    class QuotaAccuracyTests {

        @Test
        @DisplayName("挂号成功后名额应该正确扣减")
        void shouldDecreaseQuotaAfterAppointment() {
            int initialAvailable = testSchedule.getScheduleAvailable();

            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));
            when(departmentRepository.findById(testSchedule.getDepartmentId())).thenReturn(Optional.of(testDepartment));
            when(hospitalRepository.findById(testDepartment.getHospitalId())).thenReturn(Optional.of(testHospital));
            when(scheduleService.decreaseAvailable(testSchedule.getScheduleId())).thenReturn(true);
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(doctorRepository.findById(testDoctor.getDoctorId())).thenReturn(Optional.of(testDoctor));

            appointmentService.createAppointment(testPatient.getPatientId(), testSchedule.getScheduleId());

            verify(scheduleService).decreaseAvailable(testSchedule.getScheduleId());
        }

        @Test
        @DisplayName("名额为零时排班状态应该变为已满")
        void shouldMarkScheduleAsFullWhenQuotaZero() {
            testSchedule.setScheduleAvailable(1);

            when(scheduleService.decreaseAvailable(testSchedule.getScheduleId())).thenAnswer(inv -> {
                testSchedule.setScheduleAvailable(0);
                testSchedule.setScheduleStatus("full");
                return true;
            });
            when(patientService.getPatientById(testPatient.getPatientId())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(testPatient.getPatientId())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));
            when(departmentRepository.findById(testSchedule.getDepartmentId())).thenReturn(Optional.of(testDepartment));
            when(hospitalRepository.findById(testDepartment.getHospitalId())).thenReturn(Optional.of(testHospital));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(doctorRepository.findById(testDoctor.getDoctorId())).thenReturn(Optional.of(testDoctor));

            appointmentService.createAppointment(testPatient.getPatientId(), testSchedule.getScheduleId());

            assertEquals(0, testSchedule.getScheduleAvailable(), "名额应该为0");
            assertEquals("full", testSchedule.getScheduleStatus(), "状态应该变为已满");
        }

        @Test
        @DisplayName("多个患者依次挂号时名额应该依次扣减")
        void shouldDecreaseQuotaSequentially() {
            int quota = 5;
            testSchedule.setScheduleAvailable(quota);

            when(patientService.getPatientById(anyString())).thenReturn(Optional.of(testPatient));
            when(patientService.isPatientActive(anyString())).thenReturn(true);
            when(scheduleService.getScheduleById(testSchedule.getScheduleId())).thenReturn(Optional.of(testSchedule));
            when(departmentRepository.findById(testSchedule.getDepartmentId())).thenReturn(Optional.of(testDepartment));
            when(hospitalRepository.findById(testDepartment.getHospitalId())).thenReturn(Optional.of(testHospital));
            when(scheduleService.decreaseAvailable(testSchedule.getScheduleId())).thenAnswer(inv -> {
                if (testSchedule.getScheduleAvailable() > 0) {
                    testSchedule.setScheduleAvailable(testSchedule.getScheduleAvailable() - 1);
                    if (testSchedule.getScheduleAvailable() == 0) {
                        testSchedule.setScheduleStatus("full");
                    }
                    return true;
                }
                return false;
            });
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(doctorRepository.findById(testDoctor.getDoctorId())).thenReturn(Optional.of(testDoctor));

            for (int i = 0; i < quota; i++) {
                String patientId = "patient_" + i;
                appointmentService.createAppointment(patientId, testSchedule.getScheduleId());
            }

            assertEquals(0, testSchedule.getScheduleAvailable(), "所有名额应该被扣减");
            assertEquals("full", testSchedule.getScheduleStatus(), "最后应该变为已满状态");
        }
    }
}
