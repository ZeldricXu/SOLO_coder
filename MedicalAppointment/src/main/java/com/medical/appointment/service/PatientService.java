package com.medical.appointment.service;

import com.medical.appointment.entity.Patient;
import com.medical.appointment.repository.PatientRepository;
import com.medical.appointment.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PatientService {
    
    private final PatientRepository patientRepository;
    
    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }
    
    public Patient createPatient(Patient patient) {
        patient.setPatientId(IdGenerator.generatePatientId());
        patient.setRegisteredAt(LocalDateTime.now());
        if (patient.getPatientStatus() == null) {
            patient.setPatientStatus("active");
        }
        if (patient.getAppointmentCount() == null) {
            patient.setAppointmentCount(0);
        }
        if (patient.getVisitCount() == null) {
            patient.setVisitCount(0);
        }
        return patientRepository.save(patient);
    }
    
    public Optional<Patient> getPatientById(String patientId) {
        return patientRepository.findById(patientId);
    }
    
    public Optional<Patient> getPatientByPhone(String phone) {
        return patientRepository.findByPatientPhone(phone);
    }
    
    public Optional<Patient> getPatientByIdNumber(String idNumber) {
        return patientRepository.findByPatientIdNumber(idNumber);
    }
    
    public List<Patient> getAllPatients() {
        return patientRepository.findAll();
    }
    
    public List<Patient> getActivePatients() {
        return patientRepository.findByPatientStatus("active");
    }
    
    public Patient updatePatient(String patientId, Patient patientDetails) {
        return patientRepository.findById(patientId)
                .map(patient -> {
                    if (patientDetails.getPatientName() != null) {
                        patient.setPatientName(patientDetails.getPatientName());
                    }
                    if (patientDetails.getPatientPhone() != null) {
                        patient.setPatientPhone(patientDetails.getPatientPhone());
                    }
                    if (patientDetails.getPatientIdNumber() != null) {
                        patient.setPatientIdNumber(patientDetails.getPatientIdNumber());
                    }
                    if (patientDetails.getPatientStatus() != null) {
                        patient.setPatientStatus(patientDetails.getPatientStatus());
                    }
                    return patientRepository.save(patient);
                })
                .orElseThrow(() -> new RuntimeException("患者不存在: " + patientId));
    }
    
    public void deletePatient(String patientId) {
        patientRepository.deleteById(patientId);
    }
    
    public Patient freezePatient(String patientId) {
        return updatePatientStatus(patientId, "frozen");
    }
    
    public Patient unfreezePatient(String patientId) {
        return updatePatientStatus(patientId, "active");
    }
    
    private Patient updatePatientStatus(String patientId, String status) {
        return patientRepository.findById(patientId)
                .map(patient -> {
                    patient.setPatientStatus(status);
                    return patientRepository.save(patient);
                })
                .orElseThrow(() -> new RuntimeException("患者不存在: " + patientId));
    }
    
    public void incrementAppointmentCount(String patientId) {
        patientRepository.findById(patientId).ifPresent(patient -> {
            patient.setAppointmentCount(patient.getAppointmentCount() + 1);
            patientRepository.save(patient);
        });
    }
    
    public void decrementAppointmentCount(String patientId) {
        patientRepository.findById(patientId).ifPresent(patient -> {
            if (patient.getAppointmentCount() > 0) {
                patient.setAppointmentCount(patient.getAppointmentCount() - 1);
                patientRepository.save(patient);
            }
        });
    }
    
    public void incrementVisitCount(String patientId) {
        patientRepository.findById(patientId).ifPresent(patient -> {
            patient.setVisitCount(patient.getVisitCount() + 1);
            patientRepository.save(patient);
        });
    }
    
    public boolean isPatientActive(String patientId) {
        return patientRepository.findById(patientId)
                .map(patient -> "active".equals(patient.getPatientStatus()))
                .orElse(false);
    }
}
