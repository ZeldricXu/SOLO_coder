package com.fitnesscenter.repository;

import com.fitnesscenter.model.Training;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingRepository extends JpaRepository<Training, String> {
    
    Optional<Training> findByTrainingId(String trainingId);
    
    List<Training> findByMemberId(String memberId);
    
    List<Training> findByCourseId(String courseId);
    
    List<Training> findByMemberIdAndTrainingTimeBetween(String memberId, Instant startTime, Instant endTime);
    
    List<Training> findByTrainingTimeBetween(Instant startTime, Instant endTime);
    
    @Query("SELECT SUM(t.trainingCalories) FROM Training t WHERE t.memberId = ?1")
    Integer sumCaloriesByMemberId(String memberId);
    
    @Query("SELECT COUNT(t) FROM Training t WHERE t.memberId = ?1")
    Long countByMemberId(String memberId);
    
    @Query("SELECT SUM(t.trainingCalories) FROM Training t WHERE t.trainingTime BETWEEN ?1 AND ?2")
    Integer sumCaloriesByTrainingTimeBetween(Instant startTime, Instant endTime);
    
    boolean existsByTrainingId(String trainingId);
}
