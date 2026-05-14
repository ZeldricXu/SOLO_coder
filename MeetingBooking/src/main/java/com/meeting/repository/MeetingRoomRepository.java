package com.meeting.repository;

import com.meeting.entity.MeetingRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingRoomRepository extends JpaRepository<MeetingRoom, String> {

    Optional<MeetingRoom> findByRoomId(String roomId);

    List<MeetingRoom> findByRoomStatus(String roomStatus);

    List<MeetingRoom> findByRoomCapacityGreaterThanEqual(Integer capacity);

    List<MeetingRoom> findByRoomLocationContaining(String location);

    @Query("SELECT r FROM MeetingRoom r WHERE r.roomStatus = :status AND r.roomCapacity >= :minCapacity")
    List<MeetingRoom> findAvailableRooms(@Param("status") String status, @Param("minCapacity") Integer minCapacity);

    boolean existsByRoomId(String roomId);
}
