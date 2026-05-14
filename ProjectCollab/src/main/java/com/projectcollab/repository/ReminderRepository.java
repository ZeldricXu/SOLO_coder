package com.projectcollab.repository;

import com.projectcollab.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, String> {
    List<Reminder> findByProject_ProjectId(String projectId);
    List<Reminder> findByTaskId(String taskId);
    List<Reminder> findByUserId(String userId);
    List<Reminder> findByReminderStatus(String status);
}
