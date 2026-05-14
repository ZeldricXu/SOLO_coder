package com.social.service;

import com.social.entity.HistoryRecord;
import com.social.repository.HistoryRecordRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    @Transactional
    public HistoryRecord recordHistory(String userId, String recordType, String targetId, String content) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setUserId(userId);
        record.setRecordType(recordType);
        record.setTargetId(targetId);
        record.setRecordContent(content);
        return historyRecordRepository.save(record);
    }

    @Transactional
    public void recordFriendRequest(String fromUserId, String toUserId, String requestId) {
        recordHistory(fromUserId, "friend_request_sent", requestId, "向 " + toUserId + " 发送好友请求");
        recordHistory(toUserId, "friend_request_received", requestId, "收到 " + fromUserId + " 的好友请求");
    }

    @Transactional
    public void recordFriendshipAccepted(String userId1, String userId2, String friendshipId) {
        recordHistory(userId1, "friendship_accepted", friendshipId, "与 " + userId2 + " 成为好友");
        recordHistory(userId2, "friendship_accepted", friendshipId, "与 " + userId1 + " 成为好友");
    }

    @Transactional
    public void recordMessage(String fromUserId, String toUserId, String messageId) {
        recordHistory(fromUserId, "message_sent", messageId, "发送消息给 " + toUserId);
        recordHistory(toUserId, "message_received", messageId, "收到 " + fromUserId + " 的消息");
    }

    @Transactional
    public void recordPost(String userId, String postId) {
        recordHistory(userId, "post_created", postId, "发布了新动态");
    }

    @Transactional
    public void recordFollow(String followerId, String followingId) {
        recordHistory(followerId, "follow", followingId, "关注了 " + followingId);
    }

    public List<HistoryRecord> getUserHistory(String userId) {
        return historyRecordRepository.findByUserIdOrderByRecordTimeDesc(userId);
    }

    public List<HistoryRecord> getUserHistoryByType(String userId, String recordType) {
        return historyRecordRepository.findByUserIdAndRecordTypeOrderByRecordTimeDesc(userId, recordType);
    }
}
