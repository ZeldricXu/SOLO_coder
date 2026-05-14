package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.dto.VisitResult;
import com.medical.appointment.entity.*;
import com.medical.appointment.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("VisitService 单元测试 - 就诊模块")
@ExtendWith(MockitoExtension.class)
class VisitWithReminderServiceTest {

    @Mock
    private VisitRepository visitRepository;
    
    @Mock
    private AppointmentService appointmentService;
    
    @Mock
    private PatientService patientService;
    
    @Mock
    private DoctorService doctorService;
    
    @Mock
    private StatisticsService statisticsService;
    
    @Mock
    private HistoryService historyService;
    
    @Mock
    private PatientRepository patientRepository;
    
    @Mock
    private DoctorRepository doctorRepository;

    @InjectMocks
    private VisitService visitService;

    private Patient testPatient;
    private Doctor testDoctor;
    private Appointment appointedAppointment;
    private Appointment visitedAppointment;
    private Appointment cancelledAppointment;
    private Visit testVisit;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        
        testPatient = TestDataBuilder.createTestPatient("normal");
        testDoctor = TestDataBuilder.createTestDoctor("dept_001");
        
        appointedAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_001", testDoctor.getDoctorId(), "appointed");
        
        visitedAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_002", testDoctor.getDoctorId(), "visited");
        
        cancelledAppointment = TestDataBuilder.createTestAppointment(
                testPatient.getPatientId(), "schedule_003", testDoctor.getDoctorId(), "cancelled");
        
        testVisit = TestDataBuilder.createTestVisit(
                appointedAppointment.getAppointmentId(),
                testPatient.getPatientId(),
                testDoctor.getDoctorId(),
                "completed");
    }

    @Nested
    @DisplayName("就诊登记基础功能测试")
    class BasicVisitRegistrationTests {

        @Test
        @DisplayName("应该成功登记就诊")
        void shouldRegisterVisitSuccessfully() {
            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));

            VisitResult result = visitService.registerVisit(
                    appointedAppointment.getAppointmentId(),
                    "患者主诉发热、咳嗽",
                    "上呼吸道感染",
                    "阿莫西林 0.5g tid");

            assertNotNull(result, "就诊结果不应为空");
            assertEquals(testPatient.getPatientName(), result.getPatientName());
            assertEquals(testDoctor.getDoctorName(), result.getDoctorName());
            assertEquals("completed", result.getStatus());

            verify(visitRepository).save(any(Visit.class));
            verify(appointmentService).updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited");
            verify(patientService).incrementVisitCount(testPatient.getPatientId());
            verify(doctorService).incrementVisitCount(testDoctor.getDoctorId());
            verify(statisticsService).incrementVisitCount();
        }

        @Test
        @DisplayName("挂号不存在时应该拒绝就诊")
        void shouldRejectWhenAppointmentNotFound() {
            when(appointmentService.getAppointmentById("nonexistent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                visitService.registerVisit("nonexistent", null, null, null);
            });

            assertEquals("挂号不存在", exception.getMessage());
            verifyNoInteractions(visitRepository);
        }
    }

    @Nested
    @DisplayName("挂号状态流转生命周期测试")
    class AppointmentStatusLifecycleTests {

        @Test
        @DisplayName("已预约 -> 已就诊 状态流转")
        void testStatusFlowFromAppointedToVisited() {
            assertEquals("appointed", appointedAppointment.getAppointmentStatus(),
                    "初始状态应为已预约");

            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenAnswer(inv -> {
                        appointedAppointment.setAppointmentStatus("visited");
                        return appointedAppointment;
                    });
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));

            visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    "测试记录", "测试诊断", "测试处方");

            assertEquals("visited", appointedAppointment.getAppointmentStatus(),
                    "状态应该变为已就诊");
        }

        @Test
        @DisplayName("已预约 -> 已取消 状态流转")
        void testStatusFlowFromAppointedToCancelled() {
            assertEquals("cancelled", cancelledAppointment.getAppointmentStatus(),
                    "状态应为已取消");
            
            when(appointmentService.getAppointmentById(cancelledAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(cancelledAppointment));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                visitService.registerVisit(cancelledAppointment.getAppointmentId(),
                        null, null, null);
            });

            assertTrue(exception.getMessage().contains("已取消"), "已取消的挂号不能就诊");
        }

        @Test
        @DisplayName("已取消挂号应该拒绝就诊")
        void shouldRejectCancelledAppointment() {
            when(appointmentService.getAppointmentById(cancelledAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(cancelledAppointment));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                visitService.registerVisit(cancelledAppointment.getAppointmentId(),
                        null, null, null);
            });

            assertEquals("挂号已取消，无法就诊", exception.getMessage());
        }

        @Test
        @DisplayName("已就诊挂号应该拒绝重复登记")
        void shouldRejectDuplicateVisit() {
            when(appointmentService.getAppointmentById(visitedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(visitedAppointment));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                visitService.registerVisit(visitedAppointment.getAppointmentId(),
                        null, null, null);
            });

            assertEquals("已完成就诊，请勿重复登记", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("就诊记录信息正确性测试")
    class VisitRecordAccuracyTests {

        @Test
        @DisplayName("应该正确记录就诊信息")
        void shouldRecordVisitInformationCorrectly() {
            String visitRecord = "患者发热38.5℃，咳嗽3天，伴头痛乏力";
            String diagnosis = "急性上呼吸道感染";
            String prescription = "布洛芬缓释片 0.3g prn\n阿莫西林胶囊 0.5g tid * 5天";

            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> {
                Visit visit = inv.getArgument(0);
                assertEquals(visitRecord, visit.getVisitRecord(), "就诊记录不正确");
                assertEquals(diagnosis, visit.getVisitDiagnosis(), "诊断记录不正确");
                assertEquals(prescription, visit.getVisitPrescription(), "处方记录不正确");
                return visit;
            });
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));

            visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    visitRecord, diagnosis, prescription);

            verify(visitRepository).save(any(Visit.class));
        }

        @Test
        @DisplayName("应该正确关联患者和医生")
        void shouldAssociatePatientAndDoctorCorrectly() {
            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> {
                Visit visit = inv.getArgument(0);
                assertEquals(testPatient.getPatientId(), visit.getPatientId(), "患者ID不正确");
                assertEquals(testDoctor.getDoctorId(), visit.getDoctorId(), "医生ID不正确");
                return visit;
            });
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));

            VisitResult result = visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    null, null, null);

            assertEquals(testPatient.getPatientId(), result.getPatientId());
            assertEquals(testDoctor.getDoctorId(), result.getDoctorId());
        }
    }

    @Nested
    @DisplayName("统计计数更新测试")
    class StatisticsUpdateTests {

        @Test
        @DisplayName("就诊后应该更新患者就诊计数")
        void shouldIncrementPatientVisitCount() {
            int initialCount = testPatient.getVisitCount();

            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));
            doAnswer(inv -> {
                testPatient.setVisitCount(testPatient.getVisitCount() + 1);
                return null;
            }).when(patientService).incrementVisitCount(testPatient.getPatientId());

            visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    null, null, null);

            assertEquals(initialCount + 1, testPatient.getVisitCount());
        }

        @Test
        @DisplayName("就诊后应该更新医生就诊计数")
        void shouldIncrementDoctorVisitCount() {
            int initialCount = testDoctor.getVisitCount();

            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));
            doAnswer(inv -> {
                testDoctor.setVisitCount(testDoctor.getVisitCount() + 1);
                return null;
            }).when(doctorService).incrementVisitCount(testDoctor.getDoctorId());

            visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    null, null, null);

            assertEquals(initialCount + 1, testDoctor.getVisitCount());
        }
    }

    @Nested
    @DisplayName("历史记录测试")
    class HistoryRecordTests {

        @Test
        @DisplayName("应该记录就诊历史")
        void shouldRecordVisitHistory() {
            when(appointmentService.getAppointmentById(appointedAppointment.getAppointmentId()))
                    .thenReturn(Optional.of(appointedAppointment));
            when(visitRepository.save(any(Visit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(appointmentService.updateAppointmentStatus(
                    appointedAppointment.getAppointmentId(), "visited"))
                    .thenReturn(appointedAppointment);
            when(patientRepository.findById(testPatient.getPatientId()))
                    .thenReturn(Optional.of(testPatient));
            when(doctorRepository.findById(testDoctor.getDoctorId()))
                    .thenReturn(Optional.of(testDoctor));

            visitService.registerVisit(appointedAppointment.getAppointmentId(),
                    "测试记录", "测试诊断", "测试处方");

            verify(historyService).recordHistory(
                    eq(appointedAppointment.getAppointmentId()),
                    anyString(),
                    eq("appointed"),
                    eq("visited"),
                    anyString(),
                    anyString());
        }
    }
}
