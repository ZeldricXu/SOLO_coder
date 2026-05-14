package com.movie.repository;

import com.movie.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, String> {

    Optional<Schedule> findByScheduleId(String scheduleId);

    List<Schedule> findByMovieId(String movieId);

    List<Schedule> findByCinemaId(String cinemaId);

    @Query("SELECT s FROM Schedule s WHERE s.movieId = :movieId AND s.scheduleDate = :scheduleDate AND s.scheduleStatus = 'available'")
    List<Schedule> findByMovieIdAndScheduleDate(String movieId, LocalDate scheduleDate);

    List<Schedule> findByMovieIdAndCinemaId(String movieId, String cinemaId);

    boolean existsByScheduleId(String scheduleId);
}
