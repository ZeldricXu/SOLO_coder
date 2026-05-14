package com.movie.repository;

import com.movie.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {

    Optional<Ticket> findByTicketId(String ticketId);

    List<Ticket> findByUserId(String userId);

    List<Ticket> findByScheduleId(String scheduleId);

    List<Ticket> findByTicketStatus(String ticketStatus);

    boolean existsByTicketId(String ticketId);
}
