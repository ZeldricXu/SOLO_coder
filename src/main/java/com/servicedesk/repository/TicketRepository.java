package com.servicedesk.repository;

import com.servicedesk.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findByCustomerId(String customerId);
    List<Ticket> findByAssigneeId(String assigneeId);
    List<Ticket> findByTicketStatus(String ticketStatus);
    List<Ticket> findByTicketPriority(String ticketPriority);
    List<Ticket> findByTicketCategory(String ticketCategory);
    
    @Query("SELECT t FROM Ticket t WHERE t.createdAt BETWEEN :start AND :end")
    List<Ticket> findByCreatedAtBetween(Instant start, Instant end);
    
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.ticketStatus = 'resolved'")
    long countResolvedTickets();
    
    @Query("SELECT AVG(t.responseTimeSeconds) FROM Ticket t WHERE t.responseTimeSeconds IS NOT NULL")
    Long findAvgResponseTime();
    
    @Query("SELECT AVG(t.resolutionTimeSeconds) FROM Ticket t WHERE t.resolutionTimeSeconds IS NOT NULL")
    Long findAvgResolutionTime();
    
    boolean existsByTicketId(String ticketId);
}
