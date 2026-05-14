package com.social.repository;

import com.social.entity.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    Optional<FriendRequest> findByRequestId(String requestId);
    List<FriendRequest> findByFromUserAndRequestStatus(String fromUser, String status);
    List<FriendRequest> findByToUserAndRequestStatus(String toUser, String status);
    Optional<FriendRequest> findByFromUserAndToUserAndRequestStatus(String fromUser, String toUser, String status);
}
