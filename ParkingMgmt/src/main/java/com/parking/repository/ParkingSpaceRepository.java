package com.parking.repository;

import com.parking.entity.ParkingSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, String> {
    Optional<ParkingSpace> findBySpaceId(String spaceId);

    @Query("SELECT ps FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = 'available'")
    List<ParkingSpace> findAvailableSpacesByParkingId(@Param("parkingId") String parkingId);

    @Query("SELECT ps FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = 'available' AND ps.spaceType = :spaceType")
    List<ParkingSpace> findAvailableSpacesByParkingIdAndSpaceType(@Param("parkingId") String parkingId, @Param("spaceType") String spaceType);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = 'available'")
    long countAvailableSpaces(@Param("parkingId") String parkingId);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = 'available' AND ps.spaceType = :spaceType")
    long countAvailableSpacesByType(@Param("parkingId") String parkingId, @Param("spaceType") String spaceType);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId")
    long countTotalSpaces(@Param("parkingId") String parkingId);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceType = :spaceType")
    long countTotalSpacesByType(@Param("parkingId") String parkingId, @Param("spaceType") String spaceType);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = :status")
    long countSpacesByStatus(@Param("parkingId") String parkingId, @Param("status") String status);

    @Query("SELECT COUNT(ps) FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceStatus = :status AND ps.spaceType = :spaceType")
    long countSpacesByStatusAndType(@Param("parkingId") String parkingId, @Param("status") String status, @Param("spaceType") String spaceType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ps FROM ParkingSpace ps WHERE ps.spaceId = :spaceId")
    Optional<ParkingSpace> findBySpaceIdWithLock(@Param("spaceId") String spaceId);

    @Query("SELECT ps FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId")
    List<ParkingSpace> findByParkingLotParkingId(@Param("parkingId") String parkingId);

    @Query("SELECT ps FROM ParkingSpace ps WHERE ps.parkingLot.parkingId = :parkingId AND ps.spaceType = :spaceType")
    List<ParkingSpace> findByParkingLotParkingIdAndSpaceType(@Param("parkingId") String parkingId, @Param("spaceType") String spaceType);
}
