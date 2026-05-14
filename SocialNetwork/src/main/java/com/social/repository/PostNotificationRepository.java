package com.social.repository;

import com.social.entity.PostNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PostNotificationRepository extends JpaRepository<PostNotification, Long> {
    Optional<PostNotification> findByNotificationId(String notificationId);
    List<PostNotification> findByFollowerIdOrderByScheduledAtDesc(String followerId);
    List<PostNotification> findByPostIdOrderByScheduledAtDesc(String postId);
    List<PostNotification> findByDeliveryStatusOrderByScheduledAtAsc(String deliveryStatus);
    List<PostNotification> findByDeliveryStatusInOrderByScheduledAtAsc(List<String> statuses);
    List<PostNotification> findByFollowerIdAndReadStatusOrderByScheduledAtDesc(String followerId, String readStatus);
    
    long countByDeliveryStatus(String deliveryStatus);
    long countByFollowerIdAndReadStatus(String followerId, String readStatus);
}
