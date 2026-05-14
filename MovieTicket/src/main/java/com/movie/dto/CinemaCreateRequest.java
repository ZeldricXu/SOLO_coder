package com.movie.dto;

public class CinemaCreateRequest {

    private String cinemaName;
    private String cinemaAddress;
    private String cinemaRegion;
    private String cinemaStatus;
    private Double cinemaRating;
    private Integer seatTotal;

    public CinemaCreateRequest() {
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public String getCinemaAddress() {
        return cinemaAddress;
    }

    public void setCinemaAddress(String cinemaAddress) {
        this.cinemaAddress = cinemaAddress;
    }

    public String getCinemaRegion() {
        return cinemaRegion;
    }

    public void setCinemaRegion(String cinemaRegion) {
        this.cinemaRegion = cinemaRegion;
    }

    public String getCinemaStatus() {
        return cinemaStatus;
    }

    public void setCinemaStatus(String cinemaStatus) {
        this.cinemaStatus = cinemaStatus;
    }

    public Double getCinemaRating() {
        return cinemaRating;
    }

    public void setCinemaRating(Double cinemaRating) {
        this.cinemaRating = cinemaRating;
    }

    public Integer getSeatTotal() {
        return seatTotal;
    }

    public void setSeatTotal(Integer seatTotal) {
        this.seatTotal = seatTotal;
    }
}
