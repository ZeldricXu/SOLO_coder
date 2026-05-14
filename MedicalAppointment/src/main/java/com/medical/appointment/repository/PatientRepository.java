package com.medical.appointment.repository;

import com.medical.appointment.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, String> {
    Optional<Patient> findByPatientPhone(String phone);
    Optional<Patient> findByPatientIdNumber(String idNumber);
    List<Patient> findByPatientStatus(String status);
}
