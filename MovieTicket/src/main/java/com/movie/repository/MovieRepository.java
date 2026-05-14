package com.movie.repository;

import com.movie.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, String> {

    Optional<Movie> findByMovieId(String movieId);

    List<Movie> findByMovieStatus(String movieStatus);

    List<Movie> findByMovieType(String movieType);

    boolean existsByMovieId(String movieId);
}
