package com.medical.appointment.service;

import com.medical.appointment.entity.Hospital;
import com.medical.appointment.repository.HospitalRepository;
import com.medical.appointment.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class HospitalService {
    
    private final HospitalRepository hospitalRepository;
    
    public HospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }
    
    public Hospital createHospital(Hospital hospital) {
        hospital.setHospitalId(IdGenerator.generateHospitalId());
        hospital.setCreatedAt(LocalDateTime.now());
        if (hospital.getHospitalStatus() == null) {
            hospital.setHospitalStatus("active");
        }
        return hospitalRepository.save(hospital);
    }
    
    public Optional<Hospital> getHospitalById(String hospitalId) {
        return hospitalRepository.findById(hospitalId);
    }
    
    public List<Hospital> getAllHospitals() {
        return hospitalRepository.findAll();
    }
    
    public List<Hospital> getActiveHospitals() {
        return hospitalRepository.findByHospitalStatus("active");
    }
    
    public Hospital updateHospital(String hospitalId, Hospital hospitalDetails) {
        return hospitalRepository.findById(hospitalId)
                .map(hospital -> {
                    if (hospitalDetails.getHospitalName() != null) {
                        hospital.setHospitalName(hospitalDetails.getHospitalName());
                    }
                    if (hospitalDetails.getHospitalType() != null) {
                        hospital.setHospitalType(hospitalDetails.getHospitalType());
                    }
                    if (hospitalDetails.getHospitalAddress() != null) {
                        hospital.setHospitalAddress(hospitalDetails.getHospitalAddress());
                    }
                    if (hospitalDetails.getHospitalLevel() != null) {
                        hospital.setHospitalLevel(hospitalDetails.getHospitalLevel());
                    }
                    if (hospitalDetails.getHospitalStatus() != null) {
                        hospital.setHospitalStatus(hospitalDetails.getHospitalStatus());
                    }
                    return hospitalRepository.save(hospital);
                })
                .orElseThrow(() -> new RuntimeException("医院不存在: " + hospitalId));
    }
    
    public void deleteHospital(String hospitalId) {
        hospitalRepository.deleteById(hospitalId);
    }
    
    public Hospital activateHospital(String hospitalId) {
        return updateHospitalStatus(hospitalId, "active");
    }
    
    public Hospital deactivateHospital(String hospitalId) {
        return updateHospitalStatus(hospitalId, "inactive");
    }
    
    private Hospital updateHospitalStatus(String hospitalId, String status) {
        return hospitalRepository.findById(hospitalId)
                .map(hospital -> {
                    hospital.setHospitalStatus(status);
                    return hospitalRepository.save(hospital);
                })
                .orElseThrow(() -> new RuntimeException("医院不存在: " + hospitalId));
    }
}
