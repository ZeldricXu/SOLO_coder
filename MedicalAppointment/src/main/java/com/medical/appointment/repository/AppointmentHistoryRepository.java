package com.medical.appointment.repository;

import com.medical.appointment.entity.AppointmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AppointmentHistoryRepository extends JpaRepository<AppointmentHistory, Long> {
    List<AppointmentHistory> findByAppointmentIdOrderByActionTimeDesc(String appointmentId);
    List<AppointmentHistory> findByActionType(String actionType);
}
