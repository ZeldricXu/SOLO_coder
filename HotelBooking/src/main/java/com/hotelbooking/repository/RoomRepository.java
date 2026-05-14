package com.hotelbooking.repository;

import com.hotelbooking.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, String> {
    List<Room> findByHotelId(String hotelId);
    List<Room> findByHotelIdAndRoomStatus(String hotelId, String status);
    List<Room> findByRoomType(String roomType);
    List<Room> findByHotelIdAndRoomType(String hotelId, String roomType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.roomId = :roomId")
    Optional<Room> findByIdForUpdate(@Param("roomId") String roomId);

    @Query("SELECT r FROM Room r WHERE r.hotelId = :hotelId AND r.roomStatus = 'available'")
    List<Room> findAvailableRoomsByHotelId(@Param("hotelId") String hotelId);
}
