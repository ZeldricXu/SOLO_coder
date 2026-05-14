package com.movie.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @Column(name = "movie_id", length = 50)
    private String movieId;

    @Column(name = "movie_name", nullable = false, length = 200)
    private String movieName;

    @Column(name = "movie_type", length = 50)
    private String movieType;

    @Column(name = "movie_duration")
    private Integer movieDuration;

    @Column(name = "movie_rating")
    private Double movieRating;

    @Column(name = "movie_status", length = 20)
    private String movieStatus;

    @Column(name = "movie_poster", length = 500)
    private String moviePoster;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "schedule_count")
    private Integer scheduleCount = 0;

    public Movie() {
    }

    public Movie(String movieId, String movieName, String movieType, Integer movieDuration,
                 Double movieRating, String movieStatus, String moviePoster, LocalDate releaseDate,
                 LocalDateTime createdAt) {
        this.movieId = movieId;
        this.movieName = movieName;
        this.movieType = movieType;
        this.movieDuration = movieDuration;
        this.movieRating = movieRating;
        this.movieStatus = movieStatus;
        this.moviePoster = moviePoster;
        this.releaseDate = releaseDate;
        this.createdAt = createdAt;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getMovieName() {
        return movieName;
    }

    public void setMovieName(String movieName) {
        this.movieName = movieName;
    }

    public String getMovieType() {
        return movieType;
    }

    public void setMovieType(String movieType) {
        this.movieType = movieType;
    }

    public Integer getMovieDuration() {
        return movieDuration;
    }

    public void setMovieDuration(Integer movieDuration) {
        this.movieDuration = movieDuration;
    }

    public Double getMovieRating() {
        return movieRating;
    }

    public void setMovieRating(Double movieRating) {
        this.movieRating = movieRating;
    }

    public String getMovieStatus() {
        return movieStatus;
    }

    public void setMovieStatus(String movieStatus) {
        this.movieStatus = movieStatus;
    }

    public String getMoviePoster() {
        return moviePoster;
    }

    public void setMoviePoster(String moviePoster) {
        this.moviePoster = moviePoster;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(LocalDate releaseDate) {
        this.releaseDate = releaseDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getScheduleCount() {
        return scheduleCount;
    }

    public void setScheduleCount(Integer scheduleCount) {
        this.scheduleCount = scheduleCount;
    }
}
