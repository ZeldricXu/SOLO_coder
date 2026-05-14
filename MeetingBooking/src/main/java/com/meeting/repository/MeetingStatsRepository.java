package com.meeting.repository;

import com.meeting.entity.MeetingStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MeetingStatsRepository extends JpaRepository<MeetingStats, String> {

    Optional<MeetingStats> findByStatId(String statId);

    Optional<MeetingStats> findByStatMonth(String statMonth);

    boolean existsByStatMonth(String statMonth);
}
