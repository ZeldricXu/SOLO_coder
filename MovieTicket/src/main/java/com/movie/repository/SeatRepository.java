package com.movie.repository;

import com.movie.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {

    Optional<Seat> findBySeatId(String seatId);

    List<Seat> findByScheduleId(String scheduleId);

    List<Seat> findByScheduleIdAndSeatStatus(String scheduleId, String seatStatus);

    @Query("SELECT s FROM Seat s WHERE s.scheduleId = :scheduleId AND s.seatId IN :seatIds")
    List<Seat> findByScheduleIdAndSeatIdIn(String scheduleId, List<String> seatIds);

    @Modifying
    @Query("UPDATE Seat s SET s.seatStatus = :status WHERE s.seatId IN :seatIds")
    int updateSeatStatusByIds(List<String> seatIds, String status);

    boolean existsBySeatId(String seatId);
}
