package com.eventticket.repository;

import com.eventticket.entity.ChangeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChangeRecordRepository extends JpaRepository<ChangeRecord, String> {
    List<ChangeRecord> findByTicketId(String ticketId);
    List<ChangeRecord> findByChangeType(String changeType);
    List<ChangeRecord> findByTicketIdAndChangeType(String ticketId, String changeType);
    
    @Query("SELECT SUM(c.changeAmount) FROM ChangeRecord c WHERE c.changeType = 'refund' AND c.changeStatus = 'approved'")
    Long sumApprovedRefunds();
    
    @Query("SELECT COUNT(c) FROM ChangeRecord c WHERE c.changeType = :changeType AND c.changeStatus = :status")
    long countChangeRecordsByTypeAndStatus(@Param("changeType") String changeType, @Param("status") String status);
}
