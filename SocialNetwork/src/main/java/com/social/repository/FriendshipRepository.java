package com.social.repository;

import com.social.entity.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {
    Optional<Friendship> findByFriendshipId(String friendshipId);
    List<Friendship> findByUserIdAndFriendshipStatus(String userId, String status);
    List<Friendship> findByFriendIdAndFriendshipStatus(String friendId, String status);
    Optional<Friendship> findByUserIdAndFriendIdAndFriendshipStatus(String userId, String friendId, String status);
    long countByFriendshipStatus(String status);
    boolean existsByUserIdAndFriendIdAndFriendshipStatus(String userId, String friendId, String status);
}
