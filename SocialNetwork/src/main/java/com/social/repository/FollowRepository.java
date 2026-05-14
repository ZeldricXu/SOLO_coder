package com.social.repository;

import com.social.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    Optional<Follow> findByFollowId(String followId);
    List<Follow> findByFollowerIdAndFollowStatus(String followerId, String status);
    List<Follow> findByFollowingIdAndFollowStatus(String followingId, String status);
    Optional<Follow> findByFollowerIdAndFollowingIdAndFollowStatus(String followerId, String followingId, String status);
    boolean existsByFollowerIdAndFollowingIdAndFollowStatus(String followerId, String followingId, String status);
}
