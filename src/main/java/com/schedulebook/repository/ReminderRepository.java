package com.schedulebook.repository;

import com.schedulebook.model.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    
    Optional<Reminder> findByReminderId(String reminderId);
    
    List<Reminder> findByBookingId(String bookingId);
    
    List<Reminder> findByBookingIdAndReminderStatus(String bookingId, String reminderStatus);
    
    List<Reminder> findByReminderStatus(String reminderStatus);
    
    List<Reminder> findByReminderChannel(String reminderChannel);
    
    boolean existsByBookingId(String bookingId);
}
