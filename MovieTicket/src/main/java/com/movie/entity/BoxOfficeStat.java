package com.movie.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "box_office_stats", indexes = {
    @Index(name = "idx_stat_date", columnList = "stat_date")
})
public class BoxOfficeStat {

    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "movie_id", length = 50)
    private String movieId;

    @Column(name = "cinema_id", length = 50)
    private String cinemaId;

    @Column(name = "ticket_count")
    private Integer ticketCount;

    @Column(name = "box_office", precision = 15, scale = 2)
    private BigDecimal boxOffice;

    public BoxOfficeStat() {
    }

    public BoxOfficeStat(String statId, LocalDate statDate, String movieId, String cinemaId,
                         Integer ticketCount, BigDecimal boxOffice) {
        this.statId = statId;
        this.statDate = statDate;
        this.movieId = movieId;
        this.cinemaId = cinemaId;
        this.ticketCount = ticketCount;
        this.boxOffice = boxOffice;
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(String cinemaId) {
        this.cinemaId = cinemaId;
    }

    public Integer getTicketCount() {
        return ticketCount;
    }

    public void setTicketCount(Integer ticketCount) {
        this.ticketCount = ticketCount;
    }

    public BigDecimal getBoxOffice() {
        return boxOffice;
    }

    public void setBoxOffice(BigDecimal boxOffice) {
        this.boxOffice = boxOffice;
    }
}
