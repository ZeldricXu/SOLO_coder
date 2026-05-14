package com.movie.repository;

import com.movie.entity.Cinema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CinemaRepository extends JpaRepository<Cinema, String> {

    Optional<Cinema> findByCinemaId(String cinemaId);

    List<Cinema> findByCinemaStatus(String cinemaStatus);

    List<Cinema> findByCinemaRegion(String cinemaRegion);

    boolean existsByCinemaId(String cinemaId);
}
