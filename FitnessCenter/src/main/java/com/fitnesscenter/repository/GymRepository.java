package com.fitnesscenter.repository;

import com.fitnesscenter.model.Gym;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GymRepository extends JpaRepository<Gym, String> {
    
    Optional<Gym> findByGymId(String gymId);
    
    List<Gym> findByGymStatus(String gymStatus);
    
    List<Gym> findByGymNameContaining(String keyword);
    
    boolean existsByGymId(String gymId);
}
