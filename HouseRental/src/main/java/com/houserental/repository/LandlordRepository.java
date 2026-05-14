package com.houserental.repository;

import com.houserental.entity.Landlord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LandlordRepository extends JpaRepository<Landlord, String> {

    Optional<Landlord> findByLandlordId(String landlordId);

    Optional<Landlord> findByLandlordPhone(String landlordPhone);

    List<Landlord> findByLandlordStatus(String status);

    @Query("SELECT COUNT(l) FROM Landlord l")
    long countTotalLandlords();

    @Query("SELECT COUNT(l) FROM Landlord l WHERE l.landlordStatus = :status")
    long countByStatus(@Param("status") String status);

    boolean existsByLandlordId(String landlordId);

    boolean existsByLandlordPhone(String landlordPhone);
}
