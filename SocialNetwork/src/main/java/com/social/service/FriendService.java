package com.social.service;

import com.social.entity.FriendRequest;
import com.social.entity.Friendship;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.FriendRequestRepository;
import com.social.repository.FriendshipRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class FriendService {

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private FriendRequestRepository friendRequestRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private FriendRequestCheckService friendRequestCheckService;

    @Transactional
    public FriendRequest sendFriendRequest(String fromUserId, String toUserId) {
        FriendRequestCheckService.FriendRequestCheckResult checkResult = 
                friendRequestCheckService.checkCanSendFriendRequest(fromUserId, toUserId);
        
        if (!checkResult.isCanSend()) {
            if ("privacy_rejected".equals(checkResult.getExistingStatus())) {
                throw new SocialNetworkException(403, checkResult.getReason());
            }
            throw new SocialNetworkException(400, checkResult.getReason());
        }

        FriendRequest request = new FriendRequest();
        request.setRequestId(IdGenerator.generateRequestId());
        request.setFromUser(fromUserId);
        request.setToUser(toUserId);
        request.setRequestStatus("pending");

        FriendRequest savedRequest = friendRequestRepository.save(request);
        
        historyService.recordFriendRequest(fromUserId, toUserId, savedRequest.getRequestId());

        return savedRequest;
    }

    public FriendRequestCheckService.FriendRequestCheckResult checkFriendRequestStatus(String fromUserId, String toUserId) {
        return friendRequestCheckService.checkCanSendFriendRequest(fromUserId, toUserId);
    }

    @Transactional
    public Friendship acceptFriendRequest(String requestId, String accepterUserId) {
        FriendRequest request = friendRequestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new SocialNetworkException(404, "好友请求不存在"));

        if (!"pending".equals(request.getRequestStatus())) {
            throw new SocialNetworkException(400, "好友请求已处理");
        }

        if (!request.getToUser().equals(accepterUserId)) {
            throw new SocialNetworkException(403, "无权处理此请求");
        }

        request.setRequestStatus("accepted");
        friendRequestRepository.save(request);

        Friendship friendship = createFriendship(request.getFromUser(), request.getToUser());
        
        analysisService.incrementFriendshipCount();
        historyService.recordFriendshipAccepted(request.getFromUser(), request.getToUser(), friendship.getFriendshipId());

        return friendship;
    }

    @Transactional
    public void rejectFriendRequest(String requestId, String rejecterUserId) {
        FriendRequest request = friendRequestRepository.findByRequestId(requestId)
                .orElseThrow(() -> new SocialNetworkException(404, "好友请求不存在"));

        if (!"pending".equals(request.getRequestStatus())) {
            throw new SocialNetworkException(400, "好友请求已处理");
        }

        if (!request.getToUser().equals(rejecterUserId)) {
            throw new SocialNetworkException(403, "无权处理此请求");
        }

        request.setRequestStatus("rejected");
        friendRequestRepository.save(request);
    }

    @Transactional
    public Friendship createFriendship(String userId1, String userId2) {
        Friendship friendship1 = new Friendship();
        friendship1.setFriendshipId(IdGenerator.generateFriendshipId());
        friendship1.setUserId(userId1);
        friendship1.setFriendId(userId2);
        friendship1.setFriendshipStatus("accepted");
        friendship1.setAcceptedAt(LocalDateTime.now());
        friendshipRepository.save(friendship1);

        Friendship friendship2 = new Friendship();
        friendship2.setFriendshipId(IdGenerator.generateFriendshipId());
        friendship2.setUserId(userId2);
        friendship2.setFriendId(userId1);
        friendship2.setFriendshipStatus("accepted");
        friendship2.setAcceptedAt(LocalDateTime.now());
        friendshipRepository.save(friendship2);

        return friendship1;
    }

    @Transactional
    public void removeFriend(String userId, String friendId) {
        Optional<Friendship> friendship1 = friendshipRepository.findByUserIdAndFriendIdAndFriendshipStatus(
                userId, friendId, "accepted");
        Optional<Friendship> friendship2 = friendshipRepository.findByUserIdAndFriendIdAndFriendshipStatus(
                friendId, userId, "accepted");

        friendship1.ifPresent(friendshipRepository::delete);
        friendship2.ifPresent(friendshipRepository::delete);
    }

    public boolean isFriend(String userId1, String userId2) {
        return friendshipRepository.existsByUserIdAndFriendIdAndFriendshipStatus(userId1, userId2, "accepted");
    }

    public List<User> getFriends(String userId) {
        List<Friendship> friendships = friendshipRepository.findByUserIdAndFriendshipStatus(userId, "accepted");
        List<User> friends = new ArrayList<>();
        for (Friendship f : friendships) {
            try {
                friends.add(userService.getUserById(f.getFriendId()));
            } catch (Exception e) {
            }
        }
        return friends;
    }

    public List<FriendRequest> getPendingRequests(String userId) {
        return friendRequestRepository.findByToUserAndRequestStatus(userId, "pending");
    }

    public List<FriendRequest> getSentRequests(String userId) {
        return friendRequestRepository.findByFromUserAndRequestStatus(userId, "pending");
    }

    public long countAcceptedFriendships() {
        return friendshipRepository.countByFriendshipStatus("accepted");
    }
}
