package com.cicd.server.repository;

import com.cicd.server.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    List<WebhookEvent> findByProcessedFalse();

    List<WebhookEvent> findByProjectId(Long projectId);

    List<WebhookEvent> findByEventType(String eventType);
}
