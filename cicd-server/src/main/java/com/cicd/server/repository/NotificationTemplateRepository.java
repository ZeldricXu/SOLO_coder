package com.cicd.server.repository;

import com.cicd.common.enums.NotificationChannel;
import com.cicd.server.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByEventTypeAndChannelAndIsDefaultTrue(String eventType, NotificationChannel channel);

    List<NotificationTemplate> findByEventType(String eventType);

    List<NotificationTemplate> findByEventTypeAndIsEnabledTrue(String eventType);
}
