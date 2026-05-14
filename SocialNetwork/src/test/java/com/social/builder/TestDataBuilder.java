package com.social.builder;

import com.social.entity.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static UserBuilder userBuilder() {
        return new UserBuilder();
    }

    public static FriendshipBuilder friendshipBuilder() {
        return new FriendshipBuilder();
    }

    public static FriendRequestBuilder friendRequestBuilder() {
        return new FriendRequestBuilder();
    }

    public static MessageBuilder messageBuilder() {
        return new MessageBuilder();
    }

    public static PostBuilder postBuilder() {
        return new PostBuilder();
    }

    public static InteractionBuilder interactionBuilder() {
        return new InteractionBuilder();
    }

    public static SocialStatBuilder socialStatBuilder() {
        return new SocialStatBuilder();
    }

    public static FollowBuilder followBuilder() {
        return new FollowBuilder();
    }

    public static GroupBuilder groupBuilder() {
        return new GroupBuilder();
    }

    public static GroupMemberBuilder groupMemberBuilder() {
        return new GroupMemberBuilder();
    }

    public static PrivacySettingBuilder privacySettingBuilder() {
        return new PrivacySettingBuilder();
    }

    public static HistoryRecordBuilder historyRecordBuilder() {
        return new HistoryRecordBuilder();
    }

    public static PostNotificationBuilder postNotificationBuilder() {
        return new PostNotificationBuilder();
    }

    public static class UserBuilder {
        private String userId = generateId("user");
        private String userName = "测试用户";
        private String userPhone = "13800138000";
        private String userAvatar = "http://example.com/avatar.jpg";
        private String userStatus = "active";
        private String userLevel = "normal";
        private boolean online = false;

        public UserBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public UserBuilder userName(String userName) {
            this.userName = userName;
            return this;
        }

        public UserBuilder userPhone(String userPhone) {
            this.userPhone = userPhone;
            return this;
        }

        public UserBuilder userAvatar(String userAvatar) {
            this.userAvatar = userAvatar;
            return this;
        }

        public UserBuilder userStatus(String userStatus) {
            this.userStatus = userStatus;
            return this;
        }

        public UserBuilder userLevel(String userLevel) {
            this.userLevel = userLevel;
            return this;
        }

        public UserBuilder online(boolean online) {
            this.online = online;
            return this;
        }

        public User build() {
            User user = new User();
            user.setUserId(userId);
            user.setUserName(userName);
            user.setUserPhone(userPhone);
            user.setUserAvatar(userAvatar);
            user.setUserStatus(userStatus);
            user.setUserLevel(userLevel);
            user.setOnline(online);
            user.setRegisteredAt(LocalDateTime.now());
            return user;
        }

        public List<User> buildMultiple(int count) {
            List<User> users = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                users.add(userBuilder()
                        .userId(generateId("user"))
                        .userName("测试用户" + (i + 1))
                        .userPhone("13800138" + String.format("%03d", i + 1))
                        .build());
            }
            return users;
        }
    }

    public static class FriendshipBuilder {
        private String friendshipId = generateId("friendship");
        private String userId = generateId("user");
        private String friendId = generateId("user");
        private String friendshipStatus = "accepted";

        public FriendshipBuilder friendshipId(String friendshipId) {
            this.friendshipId = friendshipId;
            return this;
        }

        public FriendshipBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public FriendshipBuilder friendId(String friendId) {
            this.friendId = friendId;
            return this;
        }

        public FriendshipBuilder friendshipStatus(String friendshipStatus) {
            this.friendshipStatus = friendshipStatus;
            return this;
        }

        public Friendship build() {
            Friendship friendship = new Friendship();
            friendship.setFriendshipId(friendshipId);
            friendship.setUserId(userId);
            friendship.setFriendId(friendId);
            friendship.setFriendshipStatus(friendshipStatus);
            friendship.setFriendshipTime(LocalDateTime.now());
            friendship.setAcceptedAt(LocalDateTime.now());
            return friendship;
        }
    }

    public static class FriendRequestBuilder {
        private String requestId = generateId("request");
        private String fromUser = generateId("user");
        private String toUser = generateId("user");
        private String requestStatus = "pending";

        public FriendRequestBuilder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public FriendRequestBuilder fromUser(String fromUser) {
            this.fromUser = fromUser;
            return this;
        }

        public FriendRequestBuilder toUser(String toUser) {
            this.toUser = toUser;
            return this;
        }

        public FriendRequestBuilder requestStatus(String requestStatus) {
            this.requestStatus = requestStatus;
            return this;
        }

        public FriendRequest build() {
            FriendRequest request = new FriendRequest();
            request.setRequestId(requestId);
            request.setFromUser(fromUser);
            request.setToUser(toUser);
            request.setRequestStatus(requestStatus);
            request.setRequestTime(LocalDateTime.now());
            return request;
        }

        public List<FriendRequest> buildPendingRequests(int count) {
            List<FriendRequest> requests = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                requests.add(friendRequestBuilder()
                        .requestId(generateId("request"))
                        .requestStatus("pending")
                        .build());
            }
            return requests;
        }
    }

    public static class MessageBuilder {
        private String messageId = generateId("message");
        private String fromUser = generateId("user");
        private String toUser = generateId("user");
        private String messageType = "text";
        private String messageContent = "测试消息内容";
        private String messageStatus = "sent";
        private boolean needsConfirmation = false;
        private boolean confirmed = false;
        private int retryCount = 0;
        private int maxRetryCount = 3;

        public MessageBuilder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public MessageBuilder fromUser(String fromUser) {
            this.fromUser = fromUser;
            return this;
        }

        public MessageBuilder toUser(String toUser) {
            this.toUser = toUser;
            return this;
        }

        public MessageBuilder messageType(String messageType) {
            this.messageType = messageType;
            return this;
        }

        public MessageBuilder messageContent(String messageContent) {
            this.messageContent = messageContent;
            return this;
        }

        public MessageBuilder messageStatus(String messageStatus) {
            this.messageStatus = messageStatus;
            return this;
        }

        public MessageBuilder needsConfirmation(boolean needsConfirmation) {
            this.needsConfirmation = needsConfirmation;
            return this;
        }

        public MessageBuilder confirmed(boolean confirmed) {
            this.confirmed = confirmed;
            return this;
        }

        public MessageBuilder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public MessageBuilder maxRetryCount(int maxRetryCount) {
            this.maxRetryCount = maxRetryCount;
            return this;
        }

        public Message build() {
            Message message = new Message();
            message.setMessageId(messageId);
            message.setFromUser(fromUser);
            message.setToUser(toUser);
            message.setMessageType(messageType);
            message.setMessageContent(messageContent);
            message.setMessageStatus(messageStatus);
            message.setNeedsConfirmation(needsConfirmation);
            message.setConfirmed(confirmed);
            message.setRetryCount(retryCount);
            message.setMaxRetryCount(maxRetryCount);
            message.setSentAt(LocalDateTime.now());
            if ("delivered".equals(messageStatus) || "confirmed".equals(messageStatus)) {
                message.setDeliveredAt(LocalDateTime.now());
            }
            if ("confirmed".equals(messageStatus) || confirmed) {
                message.setConfirmedAt(LocalDateTime.now());
            }
            return message;
        }

        public List<Message> buildConversation(int count, String fromUser, String toUser) {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                messages.add(messageBuilder()
                        .messageId(generateId("message"))
                        .fromUser(fromUser)
                        .toUser(toUser)
                        .messageContent("消息内容 " + (i + 1))
                        .build());
            }
            return messages;
        }

        public List<Message> buildPendingConfirmations(int count, String toUser) {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                messages.add(messageBuilder()
                        .messageId(generateId("message"))
                        .toUser(toUser)
                        .messageStatus("sent")
                        .needsConfirmation(true)
                        .confirmed(false)
                        .build());
            }
            return messages;
        }
    }

    public static class PostBuilder {
        private String postId = generateId("post");
        private String userId = generateId("user");
        private String postContent = "测试动态内容";
        private String postType = "text";
        private int postLikes = 0;
        private int postComments = 0;
        private String postStatus = "published";

        public PostBuilder postId(String postId) {
            this.postId = postId;
            return this;
        }

        public PostBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public PostBuilder postContent(String postContent) {
            this.postContent = postContent;
            return this;
        }

        public PostBuilder postType(String postType) {
            this.postType = postType;
            return this;
        }

        public PostBuilder postLikes(int postLikes) {
            this.postLikes = postLikes;
            return this;
        }

        public PostBuilder postComments(int postComments) {
            this.postComments = postComments;
            return this;
        }

        public PostBuilder postStatus(String postStatus) {
            this.postStatus = postStatus;
            return this;
        }

        public Post build() {
            Post post = new Post();
            post.setPostId(postId);
            post.setUserId(userId);
            post.setPostContent(postContent);
            post.setPostType(postType);
            post.setPostLikes(postLikes);
            post.setPostComments(postComments);
            post.setPostStatus(postStatus);
            post.setPostTime(LocalDateTime.now());
            return post;
        }

        public List<Post> buildUserPosts(int count, String userId) {
            List<Post> posts = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                posts.add(postBuilder()
                        .postId(generateId("post"))
                        .userId(userId)
                        .postContent("动态内容 " + (i + 1))
                        .build());
            }
            return posts;
        }
    }

    public static class InteractionBuilder {
        private String interactionId = generateId("interaction");
        private String postId = generateId("post");
        private String userId = generateId("user");
        private String interactionType = "like";
        private String commentContent = null;

        public InteractionBuilder interactionId(String interactionId) {
            this.interactionId = interactionId;
            return this;
        }

        public InteractionBuilder postId(String postId) {
            this.postId = postId;
            return this;
        }

        public InteractionBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public InteractionBuilder interactionType(String interactionType) {
            this.interactionType = interactionType;
            return this;
        }

        public InteractionBuilder commentContent(String commentContent) {
            this.commentContent = commentContent;
            return this;
        }

        public Interaction build() {
            Interaction interaction = new Interaction();
            interaction.setInteractionId(interactionId);
            interaction.setPostId(postId);
            interaction.setUserId(userId);
            interaction.setInteractionType(interactionType);
            interaction.setCommentContent(commentContent);
            interaction.setInteractionTime(LocalDateTime.now());
            return interaction;
        }
    }

    public static class SocialStatBuilder {
        private String statId = generateId("stat");
        private String statMonth = "2026-05";
        private long userCount = 0;
        private long friendshipCount = 0;
        private long messageCount = 0;
        private long postCount = 0;
        private long interactionCount = 0;

        public SocialStatBuilder statId(String statId) {
            this.statId = statId;
            return this;
        }

        public SocialStatBuilder statMonth(String statMonth) {
            this.statMonth = statMonth;
            return this;
        }

        public SocialStatBuilder userCount(long userCount) {
            this.userCount = userCount;
            return this;
        }

        public SocialStatBuilder friendshipCount(long friendshipCount) {
            this.friendshipCount = friendshipCount;
            return this;
        }

        public SocialStatBuilder messageCount(long messageCount) {
            this.messageCount = messageCount;
            return this;
        }

        public SocialStatBuilder postCount(long postCount) {
            this.postCount = postCount;
            return this;
        }

        public SocialStatBuilder interactionCount(long interactionCount) {
            this.interactionCount = interactionCount;
            return this;
        }

        public SocialStat build() {
            SocialStat stat = new SocialStat();
            stat.setStatId(statId);
            stat.setStatMonth(statMonth);
            stat.setUserCount(userCount);
            stat.setFriendshipCount(friendshipCount);
            stat.setMessageCount(messageCount);
            stat.setPostCount(postCount);
            stat.setInteractionCount(interactionCount);
            return stat;
        }
    }

    public static class FollowBuilder {
        private String followId = generateId("follow");
        private String followerId = generateId("user");
        private String followingId = generateId("user");
        private String followStatus = "active";

        public FollowBuilder followId(String followId) {
            this.followId = followId;
            return this;
        }

        public FollowBuilder followerId(String followerId) {
            this.followerId = followerId;
            return this;
        }

        public FollowBuilder followingId(String followingId) {
            this.followingId = followingId;
            return this;
        }

        public FollowBuilder followStatus(String followStatus) {
            this.followStatus = followStatus;
            return this;
        }

        public Follow build() {
            Follow follow = new Follow();
            follow.setFollowId(followId);
            follow.setFollowerId(followerId);
            follow.setFollowingId(followingId);
            follow.setFollowStatus(followStatus);
            follow.setFollowTime(LocalDateTime.now());
            return follow;
        }
    }

    public static class GroupBuilder {
        private String groupId = generateId("group");
        private String groupName = "测试群组";
        private String groupDescription = "测试群组描述";
        private String groupAvatar = "http://example.com/group.jpg";
        private String ownerId = generateId("user");
        private String groupStatus = "active";
        private int maxMembers = 500;
        private int currentMembers = 1;

        public GroupBuilder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public GroupBuilder groupName(String groupName) {
            this.groupName = groupName;
            return this;
        }

        public GroupBuilder groupDescription(String groupDescription) {
            this.groupDescription = groupDescription;
            return this;
        }

        public GroupBuilder groupAvatar(String groupAvatar) {
            this.groupAvatar = groupAvatar;
            return this;
        }

        public GroupBuilder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public GroupBuilder groupStatus(String groupStatus) {
            this.groupStatus = groupStatus;
            return this;
        }

        public GroupBuilder maxMembers(int maxMembers) {
            this.maxMembers = maxMembers;
            return this;
        }

        public GroupBuilder currentMembers(int currentMembers) {
            this.currentMembers = currentMembers;
            return this;
        }

        public Group build() {
            Group group = new Group();
            group.setGroupId(groupId);
            group.setGroupName(groupName);
            group.setGroupDescription(groupDescription);
            group.setGroupAvatar(groupAvatar);
            group.setOwnerId(ownerId);
            group.setGroupStatus(groupStatus);
            group.setMaxMembers(maxMembers);
            group.setCurrentMembers(currentMembers);
            return group;
        }
    }

    public static class GroupMemberBuilder {
        private String memberId = generateId("gm");
        private String groupId = generateId("group");
        private String userId = generateId("user");
        private String memberRole = "member";
        private String memberStatus = "active";

        public GroupMemberBuilder memberId(String memberId) {
            this.memberId = memberId;
            return this;
        }

        public GroupMemberBuilder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public GroupMemberBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public GroupMemberBuilder memberRole(String memberRole) {
            this.memberRole = memberRole;
            return this;
        }

        public GroupMemberBuilder memberStatus(String memberStatus) {
            this.memberStatus = memberStatus;
            return this;
        }

        public GroupMember build() {
            GroupMember member = new GroupMember();
            member.setMemberId(memberId);
            member.setGroupId(groupId);
            member.setUserId(userId);
            member.setMemberRole(memberRole);
            member.setMemberStatus(memberStatus);
            member.setJoinedAt(LocalDateTime.now());
            return member;
        }
    }

    public static class PrivacySettingBuilder {
        private String privacyId = generateId("privacy");
        private String userId = generateId("user");
        private String friendRequestPolicy = "all";
        private String messagePolicy = "all";
        private String postVisibility = "public";
        private String profileVisibility = "public";

        public PrivacySettingBuilder privacyId(String privacyId) {
            this.privacyId = privacyId;
            return this;
        }

        public PrivacySettingBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public PrivacySettingBuilder friendRequestPolicy(String friendRequestPolicy) {
            this.friendRequestPolicy = friendRequestPolicy;
            return this;
        }

        public PrivacySettingBuilder messagePolicy(String messagePolicy) {
            this.messagePolicy = messagePolicy;
            return this;
        }

        public PrivacySettingBuilder postVisibility(String postVisibility) {
            this.postVisibility = postVisibility;
            return this;
        }

        public PrivacySettingBuilder profileVisibility(String profileVisibility) {
            this.profileVisibility = profileVisibility;
            return this;
        }

        public PrivacySetting build() {
            PrivacySetting setting = new PrivacySetting();
            setting.setPrivacyId(privacyId);
            setting.setUserId(userId);
            setting.setFriendRequestPolicy(friendRequestPolicy);
            setting.setMessagePolicy(messagePolicy);
            setting.setPostVisibility(postVisibility);
            setting.setProfileVisibility(profileVisibility);
            return setting;
        }
    }

    public static class HistoryRecordBuilder {
        private String historyId = generateId("history");
        private String userId = generateId("user");
        private String recordType = "message_sent";
        private String targetId = generateId("message");
        private String recordContent = "测试历史记录";

        public HistoryRecordBuilder historyId(String historyId) {
            this.historyId = historyId;
            return this;
        }

        public HistoryRecordBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public HistoryRecordBuilder recordType(String recordType) {
            this.recordType = recordType;
            return this;
        }

        public HistoryRecordBuilder targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        public HistoryRecordBuilder recordContent(String recordContent) {
            this.recordContent = recordContent;
            return this;
        }

        public HistoryRecord build() {
            HistoryRecord record = new HistoryRecord();
            record.setHistoryId(historyId);
            record.setUserId(userId);
            record.setRecordType(recordType);
            record.setTargetId(targetId);
            record.setRecordContent(recordContent);
            record.setRecordTime(LocalDateTime.now());
            return record;
        }

        public List<HistoryRecord> buildUserHistory(int count, String userId) {
            List<HistoryRecord> records = new ArrayList<>();
            String[] types = {"message_sent", "message_received", "friend_request_sent", "post_created"};
            for (int i = 0; i < count; i++) {
                records.add(historyRecordBuilder()
                        .historyId(generateId("history"))
                        .userId(userId)
                        .recordType(types[i % types.length])
                        .recordContent("历史记录 " + (i + 1))
                        .build());
            }
            return records;
        }
    }

    public static class PostNotificationBuilder {
        private String notificationId = generateId("notification");
        private String postId = generateId("post");
        private String followerId = generateId("user");
        private String postAuthorId = generateId("user");
        private String notificationStatus = "pending";
        private String deliveryStatus = "queued";
        private String readStatus = "unread";
        private int retryCount = 0;

        public PostNotificationBuilder notificationId(String notificationId) {
            this.notificationId = notificationId;
            return this;
        }

        public PostNotificationBuilder postId(String postId) {
            this.postId = postId;
            return this;
        }

        public PostNotificationBuilder followerId(String followerId) {
            this.followerId = followerId;
            return this;
        }

        public PostNotificationBuilder postAuthorId(String postAuthorId) {
            this.postAuthorId = postAuthorId;
            return this;
        }

        public PostNotificationBuilder notificationStatus(String notificationStatus) {
            this.notificationStatus = notificationStatus;
            return this;
        }

        public PostNotificationBuilder deliveryStatus(String deliveryStatus) {
            this.deliveryStatus = deliveryStatus;
            return this;
        }

        public PostNotificationBuilder readStatus(String readStatus) {
            this.readStatus = readStatus;
            return this;
        }

        public PostNotificationBuilder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public PostNotification build() {
            PostNotification notification = new PostNotification();
            notification.setNotificationId(notificationId);
            notification.setPostId(postId);
            notification.setFollowerId(followerId);
            notification.setPostAuthorId(postAuthorId);
            notification.setNotificationStatus(notificationStatus);
            notification.setDeliveryStatus(deliveryStatus);
            notification.setReadStatus(readStatus);
            notification.setRetryCount(retryCount);
            notification.setScheduledAt(LocalDateTime.now());
            if ("delivered".equals(deliveryStatus)) {
                notification.setSentAt(LocalDateTime.now());
            }
            if ("read".equals(readStatus)) {
                notification.setReadAt(LocalDateTime.now());
            }
            return notification;
        }

        public List<PostNotification> buildUserNotifications(int count, String followerId) {
            List<PostNotification> notifications = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                notifications.add(postNotificationBuilder()
                        .notificationId(generateId("notification"))
                        .followerId(followerId)
                        .deliveryStatus(i % 2 == 0 ? "delivered" : "queued")
                        .build());
            }
            return notifications;
        }
    }
}
