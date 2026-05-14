package com.social.service;

import com.social.entity.Follow;
import com.social.entity.Post;
import com.social.entity.PostNotification;
import com.social.entity.User;
import com.social.repository.FollowRepository;
import com.social.repository.PostNotificationRepository;
import com.social.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class PostPushService {

    private static final Logger logger = LoggerFactory.getLogger(PostPushService.class);

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private PostNotificationRepository postNotificationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private FriendService friendService;

    public static class PushResult {
        private String postId;
        private int totalFollowers;
        private int notificationsCreated;
        private int deliveredNow;
        private int queuedForLater;

        public String getPostId() {
            return postId;
        }

        public void setPostId(String postId) {
            this.postId = postId;
        }

        public int getTotalFollowers() {
            return totalFollowers;
        }

        public void setTotalFollowers(int totalFollowers) {
            this.totalFollowers = totalFollowers;
        }

        public int getNotificationsCreated() {
            return notificationsCreated;
        }

        public void setNotificationsCreated(int notificationsCreated) {
            this.notificationsCreated = notificationsCreated;
        }

        public int getDeliveredNow() {
            return deliveredNow;
        }

        public void setDeliveredNow(int deliveredNow) {
            this.deliveredNow = deliveredNow;
        }

        public int getQueuedForLater() {
            return queuedForLater;
        }

        public void setQueuedForLater(int queuedForLater) {
            this.queuedForLater = queuedForLater;
        }
    }

    @Async
    @Transactional
    public PushResult asyncPushToFollowers(Post post) {
        logger.info("异步推送动态: postId={}, userId={}", post.getPostId(), post.getUserId());
        
        PushResult result = new PushResult();
        result.setPostId(post.getPostId());

        List<Follow> follows = followRepository.findByFollowingIdAndFollowStatus(post.getUserId(), "active");
        result.setTotalFollowers(follows.size());
        
        String postVisibility = privacyService.getPostVisibility(post.getUserId());
        
        int notificationsCreated = 0;
        int deliveredNow = 0;
        int queuedForLater = 0;

        for (Follow follow : follows) {
            try {
                User follower = userService.getUserById(follow.getFollowerId());
                
                boolean canSeePost = checkPostVisibility(post.getUserId(), follower.getUserId(), postVisibility);
                
                if (canSeePost) {
                    PostNotification notification = createNotification(post, follow.getFollowerId(), follower.isOnline());
                    
                    notificationsCreated++;
                    if (follower.isOnline()) {
                        deliveredNow++;
                    } else {
                        queuedForLater++;
                    }
                }
            } catch (Exception e) {
                logger.warn("创建动态通知失败: followerId={}, error={}", 
                        follow.getFollowerId(), e.getMessage());
            }
        }

        result.setNotificationsCreated(notificationsCreated);
        result.setDeliveredNow(deliveredNow);
        result.setQueuedForLater(queuedForLater);

        logger.info("动态推送完成: postId={}, 总粉丝={}, 通知数={}, 立即送达={}, 排队={}",
                post.getPostId(), result.getTotalFollowers(), result.getNotificationsCreated(),
                result.getDeliveredNow(), result.getQueuedForLater());

        return result;
    }

    private boolean checkPostVisibility(String authorId, String viewerId, String visibility) {
        if ("public".equals(visibility)) {
            return true;
        } else if ("friends_only".equals(visibility)) {
            return isFriend(authorId, viewerId);
        } else if ("private".equals(visibility)) {
            return authorId.equals(viewerId);
        } else if ("followers_only".equals(visibility)) {
            return true;
        }
        return true;
    }

    private boolean isFriend(String userId1, String userId2) {
        if (friendService == null) {
            return false;
        }
        try {
            return friendService.isFriend(userId1, userId2);
        } catch (Exception e) {
            logger.warn("检查好友关系失败: userId1={}, userId2={}, error={}", 
                    userId1, userId2, e.getMessage());
            return false;
        }
    }

    private PostNotification createNotification(Post post, String followerId, boolean isOnline) {
        PostNotification notification = new PostNotification();
        notification.setNotificationId(IdGenerator.generateId("notification"));
        notification.setPostId(post.getPostId());
        notification.setFollowerId(followerId);
        notification.setPostAuthorId(post.getUserId());
        notification.setNotificationStatus("pending");
        notification.setReadStatus("unread");
        notification.setRetryCount(0);

        if (isOnline) {
            notification.setDeliveryStatus("delivered");
            notification.setSentAt(LocalDateTime.now());
        } else {
            notification.setDeliveryStatus("queued");
        }

        return postNotificationRepository.save(notification);
    }

    @Transactional
    public List<PostNotification> processQueuedNotifications() {
        List<String> statuses = Arrays.asList("queued", "failed");
        List<PostNotification> queuedNotifications = 
                postNotificationRepository.findByDeliveryStatusInOrderByScheduledAtAsc(statuses);

        for (PostNotification notification : queuedNotifications) {
            try {
                User follower = userService.getUserById(notification.getFollowerId());
                
                if (follower.isOnline()) {
                    notification.setDeliveryStatus("delivered");
                    notification.setSentAt(LocalDateTime.now());
                    postNotificationRepository.save(notification);
                }
            } catch (Exception e) {
                logger.warn("处理排队通知失败: notificationId={}, error={}", 
                        notification.getNotificationId(), e.getMessage());
            }
        }

        return queuedNotifications;
    }

    @Transactional
    public PostNotification markNotificationAsRead(String notificationId, String userId) {
        PostNotification notification = postNotificationRepository.findByNotificationId(notificationId)
                .orElseThrow(() -> new com.social.exception.SocialNetworkException(404, "通知不存在"));

        if (!notification.getFollowerId().equals(userId)) {
            throw new com.social.exception.SocialNetworkException(403, "无权操作此通知");
        }

        notification.setReadStatus("read");
        notification.setReadAt(LocalDateTime.now());
        notification.setNotificationStatus("read");

        return postNotificationRepository.save(notification);
    }

    public List<PostNotification> getUserNotifications(String userId) {
        return postNotificationRepository.findByFollowerIdOrderByScheduledAtDesc(userId);
    }

    public List<PostNotification> getUnreadNotifications(String userId) {
        return postNotificationRepository.findByFollowerIdAndReadStatusOrderByScheduledAtDesc(userId, "unread");
    }

    public long countUnreadNotifications(String userId) {
        return postNotificationRepository.countByFollowerIdAndReadStatus(userId, "unread");
    }

    public long countQueuedNotifications() {
        return postNotificationRepository.countByDeliveryStatus("queued");
    }
}
