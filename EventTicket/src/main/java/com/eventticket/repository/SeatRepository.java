package com.eventticket.repository;

import com.eventticket.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {
    List<Seat> findByEventId(String eventId);
    
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId AND s.seatStatus = 'available'")
    List<Seat> findAvailableSeatsByEventId(@Param("eventId") String eventId);
    
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId AND s.seatSection = :section AND s.seatStatus = 'available'")
    List<Seat> findAvailableSeatsByEventIdAndSection(@Param("eventId") String eventId, @Param("section") String section);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.seatId = :seatId")
    Optional<Seat> findByIdWithLock(@Param("seatId") String seatId);
    
    long countByEventIdAndSeatStatus(String eventId, String seatStatus);
    
    @Query("SELECT COUNT(s) FROM Seat s WHERE s.eventId = :eventId AND s.seatStatus = 'available'")
    long countAvailableSeats(@Param("eventId") String eventId);
    
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId AND s.seatStatus = 'available' ORDER BY s.seatSection, s.seatNumber")
    List<Seat> findAvailableSeatsSorted(@Param("eventId") String eventId);
}
