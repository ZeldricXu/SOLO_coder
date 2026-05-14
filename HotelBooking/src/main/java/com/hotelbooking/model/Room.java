package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @Column(name = "room_id", length = 50)
    private String roomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotel_id")
    private Hotel hotel;

    @Column(name = "hotel_id", insertable = false, updatable = false)
    private String hotelId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "room_type", length = 50)
    private String roomType;

    @Column(name = "room_price")
    private Double roomPrice;

    @Column(name = "room_status", length = 20)
    private String roomStatus;

    @ElementCollection
    @CollectionTable(name = "room_features", joinColumns = @JoinColumn(name = "room_id"))
    @Column(name = "feature")
    private List<String> roomFeatures = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Version
    private Long version;

    public Room() {}

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public Hotel getHotel() { return hotel; }
    public void setHotel(Hotel hotel) { this.hotel = hotel; }
    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }
    public String getRoomType() { return roomType; }
    public void setRoomType(String roomType) { this.roomType = roomType; }
    public Double getRoomPrice() { return roomPrice; }
    public void setRoomPrice(Double roomPrice) { this.roomPrice = roomPrice; }
    public String getRoomStatus() { return roomStatus; }
    public void setRoomStatus(String roomStatus) { this.roomStatus = roomStatus; }
    public List<String> getRoomFeatures() { return roomFeatures; }
    public void setRoomFeatures(List<String> roomFeatures) { this.roomFeatures = roomFeatures; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
