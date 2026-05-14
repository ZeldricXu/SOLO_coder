package com.social.service;

import com.social.entity.FriendRequest;
import com.social.exception.SocialNetworkException;
import com.social.repository.FriendRequestRepository;
import com.social.repository.FriendshipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class FriendRequestCheckService {

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private FriendRequestRepository friendRequestRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PrivacyService privacyService;

    public static class FriendRequestCheckResult {
        private boolean canSend;
        private String reason;
        private String existingStatus;

        public boolean isCanSend() {
            return canSend;
        }

        public void setCanSend(boolean canSend) {
            this.canSend = canSend;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getExistingStatus() {
            return existingStatus;
        }

        public void setExistingStatus(String existingStatus) {
            this.existingStatus = existingStatus;
        }
    }

    public FriendRequestCheckResult checkCanSendFriendRequest(String fromUserId, String toUserId) {
        FriendRequestCheckResult result = new FriendRequestCheckResult();
        result.setCanSend(true);

        if (fromUserId == null || toUserId == null) {
            result.setCanSend(false);
            result.setReason("用户ID不能为空");
            return result;
        }

        if (fromUserId.equals(toUserId)) {
            result.setCanSend(false);
            result.setReason("不能添加自己为好友");
            return result;
        }

        try {
            userService.getUserById(fromUserId);
        } catch (SocialNetworkException e) {
            result.setCanSend(false);
            result.setReason("发送方用户不存在");
            return result;
        }

        try {
            userService.getUserById(toUserId);
        } catch (SocialNetworkException e) {
            result.setCanSend(false);
            result.setReason("目标用户不存在");
            return result;
        }

        if (!isUserActive(fromUserId)) {
            result.setCanSend(false);
            result.setReason("发送方用户状态不可用");
            return result;
        }

        if (!isUserActive(toUserId)) {
            result.setCanSend(false);
            result.setReason("目标用户状态不可用");
            return result;
        }

        if (isFriend(fromUserId, toUserId)) {
            result.setCanSend(false);
            result.setReason("已经是好友关系");
            result.setExistingStatus("friends");
            return result;
        }

        if (!privacyService.canReceiveFriendRequests(toUserId)) {
            result.setCanSend(false);
            result.setReason("目标用户拒绝接收好友请求");
            result.setExistingStatus("privacy_rejected");
            return result;
        }

        Optional<FriendRequest> pendingRequestFrom = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                fromUserId, toUserId, "pending");
        if (pendingRequestFrom.isPresent()) {
            result.setCanSend(false);
            result.setReason("好友请求已存在，等待确认");
            result.setExistingStatus("pending_forward");
            return result;
        }

        Optional<FriendRequest> pendingRequestTo = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                toUserId, fromUserId, "pending");
        if (pendingRequestTo.isPresent()) {
            result.setCanSend(false);
            result.setReason("对方已向您发送好友请求，等待您确认");
            result.setExistingStatus("pending_reverse");
            return result;
        }

        Optional<FriendRequest> acceptedRequest = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                fromUserId, toUserId, "accepted");
        if (acceptedRequest.isPresent()) {
            result.setCanSend(false);
            result.setReason("好友请求已接受");
            result.setExistingStatus("accepted");
            return result;
        }

        Optional<FriendRequest> rejectedRequest = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                fromUserId, toUserId, "rejected");
        if (rejectedRequest.isPresent()) {
            result.setCanSend(false);
            result.setReason("好友请求已被拒绝");
            result.setExistingStatus("rejected");
            return result;
        }

        return result;
    }

    public boolean isUserActive(String userId) {
        try {
            com.social.entity.User user = userService.getUserById(userId);
            return "active".equals(user.getUserStatus());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isFriend(String userId1, String userId2) {
        return friendshipRepository.existsByUserIdAndFriendIdAndFriendshipStatus(userId1, userId2, "accepted");
    }

    public FriendRequest getExistingPendingRequest(String fromUserId, String toUserId) {
        Optional<FriendRequest> request = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                fromUserId, toUserId, "pending");
        return request.orElse(null);
    }

    public FriendRequest getExistingReversePendingRequest(String fromUserId, String toUserId) {
        Optional<FriendRequest> request = friendRequestRepository.findByFromUserAndToUserAndRequestStatus(
                toUserId, fromUserId, "pending");
        return request.orElse(null);
    }

    public List<FriendRequest> getAllRequestsBetween(String userId1, String userId2) {
        List<FriendRequest> requests1 = friendRequestRepository.findByFromUserAndRequestStatus(userId1, "pending");
        List<FriendRequest> requests2 = friendRequestRepository.findByToUserAndRequestStatus(userId1, "pending");
        
        java.util.Set<FriendRequest> allRequests = new java.util.HashSet<>();
        for (FriendRequest r : requests1) {
            if (r.getToUser().equals(userId2)) {
                allRequests.add(r);
            }
        }
        for (FriendRequest r : requests2) {
            if (r.getFromUser().equals(userId2)) {
                allRequests.add(r);
            }
        }
        return new java.util.ArrayList<>(allRequests);
    }

    public boolean hasAnyPendingRequest(String userId) {
        List<FriendRequest> fromRequests = friendRequestRepository.findByFromUserAndRequestStatus(userId, "pending");
        List<FriendRequest> toRequests = friendRequestRepository.findByToUserAndRequestStatus(userId, "pending");
        return !fromRequests.isEmpty() || !toRequests.isEmpty();
    }
}
