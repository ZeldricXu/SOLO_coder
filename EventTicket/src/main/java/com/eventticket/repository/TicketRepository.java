package com.eventticket.repository;

import com.eventticket.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findByEventId(String eventId);
    List<Ticket> findByParticipantPhone(String participantPhone);
    List<Ticket> findByTicketStatus(String ticketStatus);
    List<Ticket> findByEventIdAndTicketStatus(String eventId, String ticketStatus);
    
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.eventId = :eventId AND t.ticketStatus IN (:statuses)")
    long countTicketsByEventIdAndStatus(@Param("eventId") String eventId, @Param("statuses") List<String> statuses);
    
    @Query("SELECT SUM(t.ticketPrice) FROM Ticket t WHERE t.eventId = :eventId AND t.ticketStatus = 'confirmed'")
    Long sumTicketPriceByEventId(@Param("eventId") String eventId);
    
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.createdAt BETWEEN :start AND :end")
    long countTicketsByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT SUM(t.ticketPrice) FROM Ticket t WHERE t.confirmedAt BETWEEN :start AND :end AND t.ticketStatus = 'confirmed'")
    Long sumConfirmedTicketPriceByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.usedAt BETWEEN :start AND :end")
    long countUsedTicketsByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    
    List<Ticket> findByParticipantId(String participantId);
}
