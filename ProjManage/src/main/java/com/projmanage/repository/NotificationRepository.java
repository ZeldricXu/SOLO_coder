package com.projmanage.repository;

import com.projmanage.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByRecipientId(String recipientId);
    List<Notification> findByRecipientIdAndIsRead(String recipientId, Boolean isRead);
    List<Notification> findByProjectId(String projectId);
}
