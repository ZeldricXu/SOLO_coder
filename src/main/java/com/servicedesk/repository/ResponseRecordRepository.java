package com.servicedesk.repository;

import com.servicedesk.entity.ResponseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResponseRecordRepository extends JpaRepository<ResponseRecord, String> {
    List<ResponseRecord> findByTicketIdOrderByResponseTimeAsc(String ticketId);
    List<ResponseRecord> findByAgentId(String agentId);
    long countByTicketId(String ticketId);
    boolean existsByTicketId(String ticketId);
}
