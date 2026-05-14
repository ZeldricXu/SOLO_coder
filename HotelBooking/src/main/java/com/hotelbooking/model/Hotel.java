package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotels")
public class Hotel {
    @Id
    @Column(name = "hotel_id", length = 50)
    private String hotelId;

    @Column(name = "hotel_name", nullable = false, length = 100)
    private String hotelName;

    @Column(name = "hotel_type", length = 50)
    private String hotelType;

    @Column(name = "hotel_address", length = 255)
    private String hotelAddress;

    @Column(name = "hotel_rating")
    private Integer hotelRating;

    @Column(name = "hotel_rooms")
    private Integer hotelRooms;

    @Column(name = "hotel_status", length = 20)
    private String hotelStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Room> rooms = new ArrayList<>();

    public Hotel() {}

    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getHotelName() { return hotelName; }
    public void setHotelName(String hotelName) { this.hotelName = hotelName; }
    public String getHotelType() { return hotelType; }
    public void setHotelType(String hotelType) { this.hotelType = hotelType; }
    public String getHotelAddress() { return hotelAddress; }
    public void setHotelAddress(String hotelAddress) { this.hotelAddress = hotelAddress; }
    public Integer getHotelRating() { return hotelRating; }
    public void setHotelRating(Integer hotelRating) { this.hotelRating = hotelRating; }
    public Integer getHotelRooms() { return hotelRooms; }
    public void setHotelRooms(Integer hotelRooms) { this.hotelRooms = hotelRooms; }
    public String getHotelStatus() { return hotelStatus; }
    public void setHotelStatus(String hotelStatus) { this.hotelStatus = hotelStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<Room> getRooms() { return rooms; }
    public void setRooms(List<Room> rooms) { this.rooms = rooms; }
}
