package com.medical.appointment.repository;

import com.medical.appointment.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    List<Schedule> findByDepartmentId(String departmentId);
    List<Schedule> findByDoctorId(String doctorId);
    List<Schedule> findByScheduleDate(LocalDate date);
    List<Schedule> findByDepartmentIdAndScheduleDate(String departmentId, LocalDate date);
    List<Schedule> findByDepartmentIdAndScheduleDateAndScheduleStatus(String departmentId, LocalDate date, String status);
    List<Schedule> findByScheduleDateAndScheduleStatus(LocalDate date, String status);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.scheduleId = :id")
    Optional<Schedule> findByIdWithLock(@Param("id") String id);
}
