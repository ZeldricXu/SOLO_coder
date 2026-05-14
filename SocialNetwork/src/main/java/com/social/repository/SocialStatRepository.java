package com.social.repository;

import com.social.entity.SocialStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SocialStatRepository extends JpaRepository<SocialStat, Long> {
    Optional<SocialStat> findByStatId(String statId);
    Optional<SocialStat> findByStatMonth(String statMonth);
}
