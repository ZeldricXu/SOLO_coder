package com.finance.repository;

import com.finance.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, String> {
    List<Reminder> findByAccountIdOrderByReminderTimeDesc(String accountId);
    List<Reminder> findByReminderType(String reminderType);
    List<Reminder> findByReminderStatus(String reminderStatus);
}
