package com.travelbooking.repository;

import com.travelbooking.model.Tourist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TouristRepository extends JpaRepository<Tourist, String> {
    Optional<Tourist> findByTouristNameAndTouristPhone(String touristName, String touristPhone);
}
