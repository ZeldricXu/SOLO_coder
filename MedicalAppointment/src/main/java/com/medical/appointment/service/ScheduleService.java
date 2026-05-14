package com.medical.appointment.service;

import com.medical.appointment.dto.ScheduleQueryResult;
import com.medical.appointment.entity.Department;
import com.medical.appointment.entity.Doctor;
import com.medical.appointment.entity.Schedule;
import com.medical.appointment.repository.DepartmentRepository;
import com.medical.appointment.repository.DoctorRepository;
import com.medical.appointment.repository.ScheduleRepository;
import com.medical.appointment.util.IdGenerator;
import com.medical.appointment.util.ScheduleStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ScheduleService {
    
    private final ScheduleRepository scheduleRepository;
    private final DoctorRepository doctorRepository;
    private final DepartmentRepository departmentRepository;
    
    public ScheduleService(ScheduleRepository scheduleRepository,
                          DoctorRepository doctorRepository,
                          DepartmentRepository departmentRepository) {
        this.scheduleRepository = scheduleRepository;
        this.doctorRepository = doctorRepository;
        this.departmentRepository = departmentRepository;
    }
    
    public Schedule createSchedule(Schedule schedule) {
        Doctor doctor = doctorRepository.findById(schedule.getDoctorId())
                .orElseThrow(() -> new RuntimeException("医生不存在: " + schedule.getDoctorId()));
        
        schedule.setScheduleId(IdGenerator.generateScheduleId());
        schedule.setDepartmentId(doctor.getDepartmentId());
        
        if (schedule.getScheduleStatus() == null) {
            schedule.setScheduleStatus(ScheduleStatus.AVAILABLE);
        }
        if (schedule.getScheduleAvailable() == null) {
            schedule.setScheduleAvailable(schedule.getScheduleQuota());
        }
        
        return scheduleRepository.save(schedule);
    }
    
    public Optional<Schedule> getScheduleById(String scheduleId) {
        return scheduleRepository.findById(scheduleId);
    }
    
    public Optional<Schedule> getScheduleByIdWithLock(String scheduleId) {
        return scheduleRepository.findByIdWithLock(scheduleId);
    }
    
    public List<Schedule> getAllSchedules() {
        return scheduleRepository.findAll();
    }
    
    public List<Schedule> getSchedulesByDepartment(String departmentId) {
        return scheduleRepository.findByDepartmentId(departmentId);
    }
    
    public List<Schedule> getSchedulesByDoctor(String doctorId) {
        return scheduleRepository.findByDoctorId(doctorId);
    }
    
    public List<Schedule> getSchedulesByDate(LocalDate date) {
        return scheduleRepository.findByScheduleDate(date);
    }
    
    public List<ScheduleQueryResult> querySchedules(String hospitalId, String departmentId, LocalDate date) {
        List<Schedule> schedules;
        
        if (date == null) {
            date = LocalDate.now();
        }
        
        if (departmentId != null) {
            Department dept = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new RuntimeException("科室不存在: " + departmentId));
            schedules = scheduleRepository.findByDepartmentIdAndScheduleDateAndScheduleStatus(
                    dept.getDepartmentId(), date, ScheduleStatus.AVAILABLE);
        } else if (hospitalId != null) {
            List<Department> departments = departmentRepository.findByHospitalId(hospitalId);
            schedules = new ArrayList<>();
            for (Department dept : departments) {
                schedules.addAll(scheduleRepository.findByDepartmentIdAndScheduleDateAndScheduleStatus(
                        dept.getDepartmentId(), date, ScheduleStatus.AVAILABLE));
            }
        } else {
            schedules = scheduleRepository.findByScheduleDateAndScheduleStatus(date, ScheduleStatus.AVAILABLE);
        }
        
        return convertToQueryResults(schedules);
    }
    
    private List<ScheduleQueryResult> convertToQueryResults(List<Schedule> schedules) {
        List<ScheduleQueryResult> results = new ArrayList<>();
        for (Schedule schedule : schedules) {
            ScheduleQueryResult result = new ScheduleQueryResult();
            result.setScheduleId(schedule.getScheduleId());
            result.setDoctorId(schedule.getDoctorId());
            result.setAvailable(schedule.getScheduleAvailable());
            result.setQuota(schedule.getScheduleQuota());
            result.setScheduleTime(schedule.getScheduleTime());
            result.setScheduleStatus(schedule.getScheduleStatus());
            
            doctorRepository.findById(schedule.getDoctorId()).ifPresent(doctor -> {
                result.setDoctorName(doctor.getDoctorName());
                result.setDoctorTitle(doctor.getDoctorTitle());
            });
            
            results.add(result);
        }
        return results;
    }
    
    public Schedule updateSchedule(String scheduleId, Schedule scheduleDetails) {
        return scheduleRepository.findById(scheduleId)
                .map(schedule -> {
                    if (scheduleDetails.getScheduleDate() != null) {
                        schedule.setScheduleDate(scheduleDetails.getScheduleDate());
                    }
                    if (scheduleDetails.getScheduleTime() != null) {
                        schedule.setScheduleTime(scheduleDetails.getScheduleTime());
                    }
                    if (scheduleDetails.getScheduleQuota() != null) {
                        schedule.setScheduleQuota(scheduleDetails.getScheduleQuota());
                    }
                    if (scheduleDetails.getScheduleAvailable() != null) {
                        schedule.setScheduleAvailable(scheduleDetails.getScheduleAvailable());
                    }
                    if (scheduleDetails.getScheduleStatus() != null) {
                        schedule.setScheduleStatus(scheduleDetails.getScheduleStatus());
                    }
                    return scheduleRepository.save(schedule);
                })
                .orElseThrow(() -> new RuntimeException("排班不存在: " + scheduleId));
    }
    
    public void deleteSchedule(String scheduleId) {
        scheduleRepository.deleteById(scheduleId);
    }
    
    public boolean decreaseAvailable(String scheduleId) {
        return scheduleRepository.findByIdWithLock(scheduleId)
                .map(schedule -> {
                    if (schedule.getScheduleAvailable() <= 0) {
                        return false;
                    }
                    schedule.setScheduleAvailable(schedule.getScheduleAvailable() - 1);
                    if (schedule.getScheduleAvailable() == 0) {
                        schedule.setScheduleStatus(ScheduleStatus.FULL);
                    }
                    scheduleRepository.save(schedule);
                    return true;
                })
                .orElse(false);
    }
    
    public boolean increaseAvailable(String scheduleId) {
        return scheduleRepository.findByIdWithLock(scheduleId)
                .map(schedule -> {
                    if (schedule.getScheduleAvailable() >= schedule.getScheduleQuota()) {
                        return false;
                    }
                    schedule.setScheduleAvailable(schedule.getScheduleAvailable() + 1);
                    if (ScheduleStatus.FULL.equals(schedule.getScheduleStatus())) {
                        schedule.setScheduleStatus(ScheduleStatus.AVAILABLE);
                    }
                    scheduleRepository.save(schedule);
                    return true;
                })
                .orElse(false);
    }
}
