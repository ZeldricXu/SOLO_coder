package com.movie.repository;

import com.movie.entity.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    List<TicketHistory> findByTicketId(String ticketId);

    List<TicketHistory> findByUserId(String userId);

    List<TicketHistory> findByMovieId(String movieId);

    List<TicketHistory> findByCinemaId(String cinemaId);

    @Query("SELECT t FROM TicketHistory t WHERE t.userId = :userId AND t.createdAt BETWEEN :startTime AND :endTime ORDER BY t.createdAt DESC")
    List<TicketHistory> findByUserIdAndCreatedAtBetween(String userId, LocalDateTime startTime, LocalDateTime endTime);

    List<TicketHistory> findByUserIdOrderByCreatedAtDesc(String userId);
}
