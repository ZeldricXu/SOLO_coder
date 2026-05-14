package com.crm.repository;

import com.crm.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, String> {
    Optional<Reminder> findByReminderId(String reminderId);
    List<Reminder> findByCustomerId(String customerId);
    List<Reminder> findBySalesId(String salesId);
    List<Reminder> findByReminderStatus(String reminderStatus);
    List<Reminder> findByReminderStatusAndReminderTimeBefore(String reminderStatus, LocalDateTime time);
}
