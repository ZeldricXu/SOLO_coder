package com.mobilestore.repository;

import com.mobilestore.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);

    List<Notification> findByRecipientIdAndIsReadOrderByCreatedAtDesc(String recipientId, Boolean isRead);

    long countByRecipientIdAndIsRead(String recipientId, Boolean isRead);
}
