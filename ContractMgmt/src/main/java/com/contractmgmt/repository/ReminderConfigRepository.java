package com.contractmgmt.repository;

import com.contractmgmt.entity.ReminderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderConfigRepository extends JpaRepository<ReminderConfig, String> {

    Optional<ReminderConfig> findByReminderId(String reminderId);

    List<ReminderConfig> findByContractId(String contractId);

    List<ReminderConfig> findByReminderStatus(String reminderStatus);

    List<ReminderConfig> findByReminderType(String reminderType);

    @Query("SELECT r FROM ReminderConfig r WHERE r.reminderTime = :date AND r.reminderStatus IN :statuses")
    List<ReminderConfig> findByReminderTimeAndStatusIn(
            @Param("date") LocalDate date,
            @Param("statuses") List<String> statuses);

    @Query("SELECT r FROM ReminderConfig r WHERE r.reminderTime <= :date AND r.reminderStatus IN :statuses")
    List<ReminderConfig> findOverdueReminders(
            @Param("date") LocalDate date,
            @Param("statuses") List<String> statuses);

    Optional<ReminderConfig> findByContractIdAndReminderType(String contractId, String reminderType);
}
