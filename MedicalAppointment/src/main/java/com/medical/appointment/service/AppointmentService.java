package com.medical.appointment.service;

import com.medical.appointment.dto.AppointmentResult;
import com.medical.appointment.entity.*;
import com.medical.appointment.repository.*;
import com.medical.appointment.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class AppointmentService {
    
    private final AppointmentRepository appointmentRepository;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final ScheduleService scheduleService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final LockService lockService;
    private final ReminderService reminderService;
    
    public AppointmentService(AppointmentRepository appointmentRepository,
                              PatientService patientService,
                              DoctorService doctorService,
                              ScheduleService scheduleService,
                              StatisticsService statisticsService,
                              HistoryService historyService,
                              HospitalRepository hospitalRepository,
                              DepartmentRepository departmentRepository,
                              DoctorRepository doctorRepository,
                              PatientRepository patientRepository,
                              LockService lockService,
                              ReminderService reminderService) {
        this.appointmentRepository = appointmentRepository;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.scheduleService = scheduleService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.hospitalRepository = hospitalRepository;
        this.departmentRepository = departmentRepository;
        this.doctorRepository = doctorRepository;
        this.patientRepository = patientRepository;
        this.lockService = lockService;
        this.reminderService = reminderService;
    }
    
    public AppointmentResult createAppointment(String patientId, String scheduleId) {
        Optional<Patient> patientOpt = patientService.getPatientById(patientId);
        if (patientOpt.isEmpty()) {
            throw new RuntimeException("患者不存在");
        }
        Patient patient = patientOpt.get();
        
        if (!patientService.isPatientActive(patientId)) {
            throw new RuntimeException("患者状态不可用");
        }
        
        String patientType = patient.getPatientType() != null ? patient.getPatientType() : "normal";
        LockService.LockResult lockResult = lockService.tryAcquireForSchedule(scheduleId, patientId, patientType);
        if (!lockResult.isAcquired()) {
            throw new RuntimeException("名额锁定失败，其他患者正在预约中，请稍后重试");
        }
        
        try {
            Optional<Schedule> scheduleOpt = scheduleService.getScheduleById(scheduleId);
            if (scheduleOpt.isEmpty()) {
                throw new RuntimeException("排班不存在");
            }
            Schedule schedule = scheduleOpt.get();
            
            if (!ScheduleStatus.canAppointment(schedule.getScheduleStatus())) {
                throw new RuntimeException("排班已满或已关闭");
            }
            
            if (schedule.getScheduleAvailable() <= 0) {
                throw new RuntimeException("挂号名额已满");
            }
            
            Optional<Department> deptOpt = departmentRepository.findById(schedule.getDepartmentId());
            if (deptOpt.isEmpty()) {
                throw new RuntimeException("科室不存在");
            }
            Department department = deptOpt.get();
            
            Optional<Hospital> hospitalOpt = hospitalRepository.findById(department.getHospitalId());
            if (hospitalOpt.isEmpty()) {
                throw new RuntimeException("医院不存在");
            }
            Hospital hospital = hospitalOpt.get();
            
            boolean success = scheduleService.decreaseAvailable(scheduleId);
            if (!success) {
                throw new RuntimeException("扣减名额失败，可能名额已满");
            }
            
            Appointment appointment = new Appointment();
            appointment.setAppointmentId(IdGenerator.generateAppointmentId());
            appointment.setPatientId(patientId);
            appointment.setScheduleId(scheduleId);
            appointment.setDoctorId(schedule.getDoctorId());
            appointment.setDepartmentId(schedule.getDepartmentId());
            appointment.setHospitalId(hospital.getHospitalId());
            appointment.setAppointmentNumber(IdGenerator.generateAppointmentNumber());
            appointment.setAppointmentStatus(AppointmentStatus.APPOINTED);
            appointment.setAppointmentTime(LocalDateTime.now());
            appointment.setCreatedAt(LocalDateTime.now());
            
            appointmentRepository.save(appointment);
            
            patientService.incrementAppointmentCount(patientId);
            doctorService.incrementAppointmentCount(schedule.getDoctorId());
            statisticsService.incrementAppointmentCount(schedule.getDepartmentId());
            historyService.recordHistory(appointment.getAppointmentId(), 
                    ActionType.CREATE, null, AppointmentStatus.APPOINTED, "SYSTEM", "预约挂号成功");
            
            try {
                reminderService.sendReminder(appointment.getAppointmentId());
            } catch (Exception e) {
                historyService.recordHistory(appointment.getAppointmentId(), 
                        ActionType.REMIND, AppointmentStatus.APPOINTED, AppointmentStatus.APPOINTED, "SYSTEM", 
                        "就诊提醒发送失败: " + e.getMessage());
            }
            
            return buildAppointmentResult(appointment, patient, schedule, hospital, department);
            
        } finally {
            lockService.releaseScheduleLock(scheduleId);
        }
    }
    
    private AppointmentResult buildAppointmentResult(Appointment appointment, Patient patient, 
                                                    Schedule schedule, Hospital hospital, 
                                                    Department department) {
        AppointmentResult result = new AppointmentResult();
        result.setAppointmentId(appointment.getAppointmentId());
        result.setNumber(appointment.getAppointmentNumber());
        result.setPatientId(patient.getPatientId());
        result.setPatientName(patient.getPatientName());
        result.setAppointmentStatus(appointment.getAppointmentStatus());
        result.setAppointmentTime(appointment.getAppointmentTime() != null ? 
                appointment.getAppointmentTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
        
        if (hospital != null) {
            result.setHospitalName(hospital.getHospitalName());
        }
        if (department != null) {
            result.setDepartmentName(department.getDepartmentName());
        }
        
        doctorRepository.findById(schedule.getDoctorId()).ifPresent(doctor -> {
            result.setDoctorName(doctor.getDoctorName());
        });
        
        return result;
    }
    
    public Optional<Appointment> getAppointmentById(String appointmentId) {
        return appointmentRepository.findById(appointmentId);
    }
    
    public Optional<Appointment> getAppointmentByNumber(String number) {
        return appointmentRepository.findByAppointmentNumber(number);
    }
    
    public List<Appointment> getAppointmentsByPatient(String patientId) {
        return appointmentRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
    }
    
    public List<Appointment> getAppointmentsByPatientAndStatus(String patientId, String status) {
        return appointmentRepository.findByPatientIdAndAppointmentStatus(patientId, status);
    }
    
    public List<Appointment> getAppointmentsBySchedule(String scheduleId) {
        return appointmentRepository.findByScheduleId(scheduleId);
    }
    
    public List<Appointment> getAppointmentsByDoctor(String doctorId) {
        return appointmentRepository.findByDoctorId(doctorId);
    }
    
    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAll();
    }
    
    public Appointment updateAppointmentStatus(String appointmentId, String status) {
        return appointmentRepository.findById(appointmentId)
                .map(appointment -> {
                    appointment.setAppointmentStatus(status);
                    return appointmentRepository.save(appointment);
                })
                .orElseThrow(() -> new RuntimeException("挂号不存在: " + appointmentId));
    }
    
    public long countByPatientAndStatus(String patientId, String status) {
        return appointmentRepository.countByPatientIdAndStatus(patientId, status);
    }
    
    public AppointmentResult getAppointmentResult(String appointmentId) {
        Optional<Appointment> apptOpt = appointmentRepository.findById(appointmentId);
        if (apptOpt.isEmpty()) {
            throw new RuntimeException("挂号不存在: " + appointmentId);
        }
        Appointment appointment = apptOpt.get();
        
        Patient patient = patientRepository.findById(appointment.getPatientId()).orElse(null);
        Schedule schedule = scheduleService.getScheduleById(appointment.getScheduleId()).orElse(null);
        Hospital hospital = appointment.getHospitalId() != null ? 
                hospitalRepository.findById(appointment.getHospitalId()).orElse(null) : null;
        Department department = appointment.getDepartmentId() != null ? 
                departmentRepository.findById(appointment.getDepartmentId()).orElse(null) : null;
        
        if (schedule == null) {
            AppointmentResult result = new AppointmentResult();
            result.setAppointmentId(appointment.getAppointmentId());
            result.setNumber(appointment.getAppointmentNumber());
            result.setAppointmentStatus(appointment.getAppointmentStatus());
            if (patient != null) {
                result.setPatientId(patient.getPatientId());
                result.setPatientName(patient.getPatientName());
            }
            if (hospital != null) {
                result.setHospitalName(hospital.getHospitalName());
            }
            if (department != null) {
                result.setDepartmentName(department.getDepartmentName());
            }
            return result;
        }
        
        return buildAppointmentResult(appointment, patient, schedule, hospital, department);
    }
}
