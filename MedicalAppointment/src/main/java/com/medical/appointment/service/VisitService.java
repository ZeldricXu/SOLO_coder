package com.medical.appointment.service;

import com.medical.appointment.dto.VisitResult;
import com.medical.appointment.entity.*;
import com.medical.appointment.repository.*;
import com.medical.appointment.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class VisitService {
    
    private final VisitRepository visitRepository;
    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    
    public VisitService(VisitRepository visitRepository,
                       AppointmentService appointmentService,
                       PatientService patientService,
                       DoctorService doctorService,
                       StatisticsService statisticsService,
                       HistoryService historyService,
                       PatientRepository patientRepository,
                       DoctorRepository doctorRepository) {
        this.visitRepository = visitRepository;
        this.appointmentService = appointmentService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
    }
    
    public VisitResult registerVisit(String appointmentId, String visitRecord, 
                                    String diagnosis, String prescription) {
        Optional<Appointment> apptOpt = appointmentService.getAppointmentById(appointmentId);
        if (apptOpt.isEmpty()) {
            throw new RuntimeException("挂号不存在");
        }
        Appointment appointment = apptOpt.get();
        
        if (AppointmentStatus.CANCELLED.equals(appointment.getAppointmentStatus())) {
            throw new RuntimeException("挂号已取消，无法就诊");
        }
        
        if (AppointmentStatus.VISITED.equals(appointment.getAppointmentStatus())) {
            throw new RuntimeException("已完成就诊，请勿重复登记");
        }
        
        if (!AppointmentStatus.canVisit(appointment.getAppointmentStatus())) {
            throw new RuntimeException("当前状态不允许就诊登记");
        }
        
        Visit visit = new Visit();
        visit.setVisitId(IdGenerator.generateVisitId());
        visit.setAppointmentId(appointmentId);
        visit.setPatientId(appointment.getPatientId());
        visit.setDoctorId(appointment.getDoctorId());
        visit.setVisitTime(LocalDateTime.now());
        visit.setVisitStatus("completed");
        visit.setVisitRecord(visitRecord);
        visit.setVisitDiagnosis(diagnosis);
        visit.setVisitPrescription(prescription);
        
        visitRepository.save(visit);
        
        String previousStatus = appointment.getAppointmentStatus();
        appointmentService.updateAppointmentStatus(appointmentId, AppointmentStatus.VISITED);
        
        patientService.incrementVisitCount(appointment.getPatientId());
        doctorService.incrementVisitCount(appointment.getDoctorId());
        statisticsService.incrementVisitCount();
        historyService.recordHistory(appointmentId, 
                ActionType.VISIT, previousStatus, AppointmentStatus.VISITED, "SYSTEM", "就诊登记成功");
        
        return buildVisitResult(visit, appointment);
    }
    
    private VisitResult buildVisitResult(Visit visit, Appointment appointment) {
        VisitResult result = new VisitResult();
        result.setVisitId(visit.getVisitId());
        result.setAppointmentId(visit.getAppointmentId());
        result.setPatientId(visit.getPatientId());
        result.setDoctorId(visit.getDoctorId());
        result.setStatus(visit.getVisitStatus());
        result.setVisitTime(visit.getVisitTime() != null ? 
                visit.getVisitTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        
        patientRepository.findById(visit.getPatientId()).ifPresent(patient -> {
            result.setPatientName(patient.getPatientName());
        });
        
        doctorRepository.findById(visit.getDoctorId()).ifPresent(doctor -> {
            result.setDoctorName(doctor.getDoctorName());
        });
        
        return result;
    }
    
    public Optional<Visit> getVisitById(String visitId) {
        return visitRepository.findById(visitId);
    }
    
    public Optional<Visit> getVisitByAppointmentId(String appointmentId) {
        return visitRepository.findByAppointmentId(appointmentId);
    }
    
    public List<Visit> getVisitsByPatient(String patientId) {
        return visitRepository.findByPatientId(patientId);
    }
    
    public List<Visit> getVisitsByDoctor(String doctorId) {
        return visitRepository.findByDoctorId(doctorId);
    }
    
    public List<Visit> getAllVisits() {
        return visitRepository.findAll();
    }
    
    public Visit updateVisit(String visitId, Visit visitDetails) {
        return visitRepository.findById(visitId)
                .map(visit -> {
                    if (visitDetails.getVisitRecord() != null) {
                        visit.setVisitRecord(visitDetails.getVisitRecord());
                    }
                    if (visitDetails.getVisitDiagnosis() != null) {
                        visit.setVisitDiagnosis(visitDetails.getVisitDiagnosis());
                    }
                    if (visitDetails.getVisitPrescription() != null) {
                        visit.setVisitPrescription(visitDetails.getVisitPrescription());
                    }
                    if (visitDetails.getVisitStatus() != null) {
                        visit.setVisitStatus(visitDetails.getVisitStatus());
                    }
                    return visitRepository.save(visit);
                })
                .orElseThrow(() -> new RuntimeException("就诊记录不存在: " + visitId));
    }
}
