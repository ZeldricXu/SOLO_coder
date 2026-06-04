package com.battle.platform.repository;

import com.battle.platform.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findBySeasonCode(String seasonCode);

    Optional<Season> findByStatus(Season.SeasonStatus status);
}
