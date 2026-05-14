package com.healthtrack.repository;

import com.healthtrack.entity.HealthReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthReminderRepository extends JpaRepository<HealthReminder, String> {
    
    List<HealthReminder> findByUserId(String userId);
    
    List<HealthReminder> findByUserIdAndEnabledTrue(String userId);
    
    List<HealthReminder> findByEnabledTrue();
    
    List<HealthReminder> findByUserIdAndReminderType(String userId, String reminderType);
}
