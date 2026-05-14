package com.medical.appointment.service;

import com.medical.appointment.entity.Appointment;
import com.medical.appointment.repository.AppointmentRepository;
import com.medical.appointment.util.ActionType;
import com.medical.appointment.util.AppointmentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class CancellationService {
    
    private final AppointmentRepository appointmentRepository;
    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final ScheduleService scheduleService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final AsyncQuotaService asyncQuotaService;
    
    public CancellationService(AppointmentRepository appointmentRepository,
                              AppointmentService appointmentService,
                              PatientService patientService,
                              DoctorService doctorService,
                              ScheduleService scheduleService,
                              StatisticsService statisticsService,
                              HistoryService historyService,
                              AsyncQuotaService asyncQuotaService) {
        this.appointmentRepository = appointmentRepository;
        this.appointmentService = appointmentService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.scheduleService = scheduleService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.asyncQuotaService = asyncQuotaService;
    }
    
    public String cancelAppointment(String appointmentId, String cancelReason) {
        Optional<Appointment> apptOpt = appointmentRepository.findById(appointmentId);
        if (apptOpt.isEmpty()) {
            throw new RuntimeException("挂号不存在");
        }
        Appointment appointment = apptOpt.get();
        
        if (AppointmentStatus.VISITED.equals(appointment.getAppointmentStatus())) {
            throw new RuntimeException("已就诊的挂号不可取消");
        }
        
        if (AppointmentStatus.CANCELLED.equals(appointment.getAppointmentStatus())) {
            throw new RuntimeException("挂号已取消，请勿重复操作");
        }
        
        if (!AppointmentStatus.canCancel(appointment.getAppointmentStatus())) {
            throw new RuntimeException("当前状态不允许取消");
        }
        
        String previousStatus = appointment.getAppointmentStatus();
        String scheduleId = appointment.getScheduleId();
        
        appointment.setAppointmentStatus(AppointmentStatus.CANCELLED);
        appointment.setCancelReason(cancelReason);
        appointmentRepository.save(appointment);
        
        String taskId = asyncQuotaService.submitQuotaRestore(scheduleId, appointmentId);
        
        patientService.decrementAppointmentCount(appointment.getPatientId());
        doctorService.decrementAppointmentCount(appointment.getDoctorId());
        statisticsService.incrementCancelCount();
        historyService.recordHistory(appointmentId, 
                ActionType.CANCEL, previousStatus, AppointmentStatus.CANCELLED, "SYSTEM", 
                (cancelReason != null ? cancelReason : "用户取消挂号") + " [异步任务ID: " + taskId + "]");
        
        return appointmentId;
    }
}
