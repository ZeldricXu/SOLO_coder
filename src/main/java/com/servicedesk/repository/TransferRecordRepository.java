package com.servicedesk.repository;

import com.servicedesk.entity.TransferRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRecordRepository extends JpaRepository<TransferRecord, String> {
    List<TransferRecord> findByTicketIdOrderByTransferTimeAsc(String ticketId);
    List<TransferRecord> findByFromAgent(String fromAgent);
    List<TransferRecord> findByToAgent(String toAgent);
}
