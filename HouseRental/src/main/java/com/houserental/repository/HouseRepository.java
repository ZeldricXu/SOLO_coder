package com.houserental.repository;

import com.houserental.entity.House;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HouseRepository extends JpaRepository<House, String>, JpaSpecificationExecutor<House> {

    Optional<House> findByHouseId(String houseId);

    List<House> findByHouseStatus(String status);

    List<House> findByLandlordId(String landlordId);

    List<House> findByLandlordIdAndHouseStatus(String landlordId, String status);

    @Query("SELECT h FROM House h WHERE h.houseRent BETWEEN :minRent AND :maxRent AND h.houseStatus = 'available'")
    List<House> findByRentRange(@Param("minRent") double minRent, @Param("maxRent") double maxRent);

    @Query("SELECT h FROM House h WHERE h.houseAddress LIKE %:keyword% AND h.houseStatus = 'available'")
    List<House> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT COUNT(h) FROM House h WHERE h.houseStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(h) FROM House h")
    long countTotalHouses();

    @Query("SELECT COUNT(h) FROM House h WHERE h.landlordId = :landlordId")
    long countByLandlordId(@Param("landlordId") String landlordId);

    boolean existsByHouseId(String houseId);
}
