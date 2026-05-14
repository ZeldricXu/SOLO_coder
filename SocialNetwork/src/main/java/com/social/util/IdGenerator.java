package com.social.util;

import java.util.UUID;

public class IdGenerator {
    
    private IdGenerator() {
    }

    public static String generateUserId() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateFriendshipId() {
        return "friendship_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateRequestId() {
        return "request_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateMessageId() {
        return "message_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generatePostId() {
        return "post_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateInteractionId() {
        return "interaction_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateFollowId() {
        return "follow_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateGroupId() {
        return "group_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateGroupMemberId() {
        return "gm_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generatePrivacyId() {
        return "privacy_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateHistoryId() {
        return "history_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
