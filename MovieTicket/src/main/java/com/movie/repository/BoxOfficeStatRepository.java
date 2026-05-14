package com.movie.repository;

import com.movie.entity.BoxOfficeStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BoxOfficeStatRepository extends JpaRepository<BoxOfficeStat, String> {

    Optional<BoxOfficeStat> findByStatId(String statId);

    List<BoxOfficeStat> findByStatDate(LocalDate statDate);

    List<BoxOfficeStat> findByMovieId(String movieId);

    List<BoxOfficeStat> findByCinemaId(String cinemaId);

    @Query("SELECT b FROM BoxOfficeStat b WHERE b.statDate = :statDate AND b.movieId = :movieId AND b.cinemaId = :cinemaId")
    Optional<BoxOfficeStat> findByStatDateAndMovieIdAndCinemaId(LocalDate statDate, String movieId, String cinemaId);

    @Query("SELECT SUM(b.ticketCount) FROM BoxOfficeStat b WHERE b.movieId = :movieId")
    Long sumTicketCountByMovieId(String movieId);

    @Query("SELECT SUM(b.boxOffice) FROM BoxOfficeStat b WHERE b.movieId = :movieId")
    java.math.BigDecimal sumBoxOfficeByMovieId(String movieId);
}
