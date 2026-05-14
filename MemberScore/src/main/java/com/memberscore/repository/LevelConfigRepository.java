package com.memberscore.repository;

import com.memberscore.entity.LevelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LevelConfigRepository extends JpaRepository<LevelConfig, Long> {
    
    Optional<LevelConfig> findByLevelId(String levelId);
    
    Optional<LevelConfig> findByLevelName(String levelName);
    
    List<LevelConfig> findByIsEnabledTrueOrderByLevelOrderAsc();
    
    @Query("SELECT l FROM LevelConfig l WHERE l.isEnabled = true AND l.levelPointsRequired <= :points ORDER BY l.levelOrder DESC")
    List<LevelConfig> findHighestLevelForPoints(@Param("points") Integer points);
    
    @Query("SELECT l FROM LevelConfig l WHERE l.isEnabled = true AND l.levelPointsRequired > :currentPoints ORDER BY l.levelOrder ASC")
    List<LevelConfig> findNextLevels(@Param("currentPoints") Integer currentPoints);
}
