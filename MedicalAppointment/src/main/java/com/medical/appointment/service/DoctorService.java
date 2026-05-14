package com.medical.appointment.service;

import com.medical.appointment.entity.Doctor;
import com.medical.appointment.repository.DoctorRepository;
import com.medical.appointment.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class DoctorService {
    
    private final DoctorRepository doctorRepository;
    
    public DoctorService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }
    
    public Doctor createDoctor(Doctor doctor) {
        doctor.setDoctorId(IdGenerator.generateDoctorId());
        doctor.setCreatedAt(LocalDateTime.now());
        if (doctor.getDoctorStatus() == null) {
            doctor.setDoctorStatus("active");
        }
        if (doctor.getDoctorRating() == null) {
            doctor.setDoctorRating(0.0);
        }
        if (doctor.getAppointmentCount() == null) {
            doctor.setAppointmentCount(0);
        }
        if (doctor.getVisitCount() == null) {
            doctor.setVisitCount(0);
        }
        return doctorRepository.save(doctor);
    }
    
    public Optional<Doctor> getDoctorById(String doctorId) {
        return doctorRepository.findById(doctorId);
    }
    
    public List<Doctor> getAllDoctors() {
        return doctorRepository.findAll();
    }
    
    public List<Doctor> getDoctorsByDepartment(String departmentId) {
        return doctorRepository.findByDepartmentId(departmentId);
    }
    
    public List<Doctor> getActiveDoctorsByDepartment(String departmentId) {
        return doctorRepository.findByDepartmentIdAndDoctorStatus(departmentId, "active");
    }
    
    public List<Doctor> getActiveDoctors() {
        return doctorRepository.findByDoctorStatus("active");
    }
    
    public Doctor updateDoctor(String doctorId, Doctor doctorDetails) {
        return doctorRepository.findById(doctorId)
                .map(doctor -> {
                    if (doctorDetails.getDoctorName() != null) {
                        doctor.setDoctorName(doctorDetails.getDoctorName());
                    }
                    if (doctorDetails.getDoctorTitle() != null) {
                        doctor.setDoctorTitle(doctorDetails.getDoctorTitle());
                    }
                    if (doctorDetails.getDepartmentId() != null) {
                        doctor.setDepartmentId(doctorDetails.getDepartmentId());
                    }
                    if (doctorDetails.getDoctorRating() != null) {
                        doctor.setDoctorRating(doctorDetails.getDoctorRating());
                    }
                    if (doctorDetails.getDoctorStatus() != null) {
                        doctor.setDoctorStatus(doctorDetails.getDoctorStatus());
                    }
                    return doctorRepository.save(doctor);
                })
                .orElseThrow(() -> new RuntimeException("医生不存在: " + doctorId));
    }
    
    public void deleteDoctor(String doctorId) {
        doctorRepository.deleteById(doctorId);
    }
    
    public void incrementAppointmentCount(String doctorId) {
        doctorRepository.findById(doctorId).ifPresent(doctor -> {
            doctor.setAppointmentCount(doctor.getAppointmentCount() + 1);
            doctorRepository.save(doctor);
        });
    }
    
    public void decrementAppointmentCount(String doctorId) {
        doctorRepository.findById(doctorId).ifPresent(doctor -> {
            if (doctor.getAppointmentCount() > 0) {
                doctor.setAppointmentCount(doctor.getAppointmentCount() - 1);
                doctorRepository.save(doctor);
            }
        });
    }
    
    public void incrementVisitCount(String doctorId) {
        doctorRepository.findById(doctorId).ifPresent(doctor -> {
            doctor.setVisitCount(doctor.getVisitCount() + 1);
            doctorRepository.save(doctor);
        });
    }
}
