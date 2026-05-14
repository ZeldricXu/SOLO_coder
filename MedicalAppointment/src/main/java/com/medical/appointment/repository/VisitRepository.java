package com.medical.appointment.repository;

import com.medical.appointment.entity.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, String> {
    List<Visit> findByPatientId(String patientId);
    List<Visit> findByDoctorId(String doctorId);
    Optional<Visit> findByAppointmentId(String appointmentId);
    List<Visit> findByVisitStatus(String status);
}
