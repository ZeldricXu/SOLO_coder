package com.servicedesk.repository;

import com.servicedesk.entity.StatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StatusLogRepository extends JpaRepository<StatusLog, String> {
    List<StatusLog> findByTicketIdOrderByChangedAtAsc(String ticketId);
}
