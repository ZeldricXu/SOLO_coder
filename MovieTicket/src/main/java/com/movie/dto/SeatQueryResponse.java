package com.movie.dto;

import java.io.Serializable;

public class SeatQueryResponse implements Serializable {

    private String seatId;
    private String seatNumber;
    private Integer seatRow;
    private Integer seatColumn;
    private String seatType;
    private String seatStatus;
    private java.math.BigDecimal seatPrice;

    public SeatQueryResponse() {
    }

    public String getSeatId() {
        return seatId;
    }

    public void setSeatId(String seatId) {
        this.seatId = seatId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    public Integer getSeatRow() {
        return seatRow;
    }

    public void setSeatRow(Integer seatRow) {
        this.seatRow = seatRow;
    }

    public Integer getSeatColumn() {
        return seatColumn;
    }

    public void setSeatColumn(Integer seatColumn) {
        this.seatColumn = seatColumn;
    }

    public String getSeatType() {
        return seatType;
    }

    public void setSeatType(String seatType) {
        this.seatType = seatType;
    }

    public String getSeatStatus() {
        return seatStatus;
    }

    public void setSeatStatus(String seatStatus) {
        this.seatStatus = seatStatus;
    }

    public java.math.BigDecimal getSeatPrice() {
        return seatPrice;
    }

    public void setSeatPrice(java.math.BigDecimal seatPrice) {
        this.seatPrice = seatPrice;
    }
}
