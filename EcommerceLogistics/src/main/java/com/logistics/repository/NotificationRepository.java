package com.logistics.repository;

import com.logistics.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findByLogisticsId(String logisticsId);

    List<Notification> findByUserId(String userId);

    List<Notification> findByUserIdAndIsRead(String userId, Boolean isRead);
}
