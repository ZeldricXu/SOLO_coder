package com.medical.appointment.repository;

import com.medical.appointment.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, String> {
    List<Appointment> findByPatientId(String patientId);
    List<Appointment> findByPatientIdAndAppointmentStatus(String patientId, String status);
    List<Appointment> findByScheduleId(String scheduleId);
    List<Appointment> findByDoctorId(String doctorId);
    List<Appointment> findByAppointmentStatus(String status);
    
    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.patientId = :patientId AND a.appointmentStatus = :status")
    Long countByPatientIdAndStatus(@Param("patientId") String patientId, @Param("status") String status);
    
    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.scheduleId = :scheduleId AND a.appointmentStatus IN :statuses")
    Long countActiveAppointmentsByScheduleId(@Param("scheduleId") String scheduleId, @Param("statuses") List<String> statuses);
    
    @Query("SELECT a FROM Appointment a WHERE a.patientId = :patientId ORDER BY a.createdAt DESC")
    List<Appointment> findByPatientIdOrderByCreatedAtDesc(@Param("patientId") String patientId);
    
    Optional<Appointment> findByAppointmentNumber(String appointmentNumber);
}
