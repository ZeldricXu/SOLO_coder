package com.eventticket.repository;

import com.eventticket.entity.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, String> {
    List<TicketHistory> findByTicketIdOrderByActionTimeDesc(String ticketId);
    
    @Query("SELECT h FROM TicketHistory h WHERE h.ticketId = :ticketId ORDER BY h.actionTime DESC")
    List<TicketHistory> findFullHistoryByTicketId(@Param("ticketId") String ticketId);
}
