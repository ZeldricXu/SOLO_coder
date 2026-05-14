package com.social.service;

import com.social.entity.Message;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.MessageRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MessageService {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private MessageConfirmationService messageConfirmationService;

    @Autowired
    private MessageRetryService messageRetryService;

    @Transactional
    public Message sendMessage(String fromUserId, String toUserId, String messageContent) {
        return sendMessage(fromUserId, toUserId, messageContent, false, 3);
    }

    @Transactional
    public Message sendMessage(String fromUserId, String toUserId, String messageContent, 
            boolean needsConfirmation, int maxRetryCount) {
        if (fromUserId == null || toUserId == null) {
            throw new SocialNetworkException(400, "用户ID不能为空");
        }
        
        if (messageContent == null || messageContent.trim().isEmpty()) {
            throw new SocialNetworkException(400, "消息内容不能为空");
        }

        User fromUser = userService.getUserById(fromUserId);
        User toUser = userService.getUserById(toUserId);

        if (!"active".equals(fromUser.getUserStatus())) {
            throw new SocialNetworkException(400, "发送方用户状态不可用");
        }

        if (!"active".equals(toUser.getUserStatus())) {
            throw new SocialNetworkException(400, "接收方用户状态不可用");
        }

        boolean isFriend = friendService.isFriend(fromUserId, toUserId);
        if (!privacyService.canReceiveMessage(fromUserId, toUserId, isFriend)) {
            throw new SocialNetworkException(403, "接收方拒绝接收消息");
        }

        Message message = new Message();
        message.setMessageId(IdGenerator.generateMessageId());
        message.setFromUser(fromUserId);
        message.setToUser(toUserId);
        message.setMessageType("text");
        message.setMessageContent(messageContent);
        message.setMessageStatus("sent");
        message.setNeedsConfirmation(needsConfirmation);
        message.setConfirmed(false);
        message.setMaxRetryCount(maxRetryCount > 0 ? maxRetryCount : 3);

        if (toUser.isOnline()) {
            message.setMessageStatus("delivered");
            message.setDeliveredAt(java.time.LocalDateTime.now());
        }

        Message savedMessage = messageRepository.save(message);
        
        analysisService.incrementMessageCount();
        historyService.recordMessage(fromUserId, toUserId, savedMessage.getMessageId());

        return savedMessage;
    }

    @Transactional
    public Message markAsRead(String messageId, String readerUserId) {
        Message message = messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new SocialNetworkException(404, "消息不存在"));

        if (!message.getToUser().equals(readerUserId)) {
            throw new SocialNetworkException(403, "无权操作此消息");
        }

        message.setMessageStatus("read");
        message.setReadAt(java.time.LocalDateTime.now());

        if (!message.isConfirmed() && message.isNeedsConfirmation()) {
            message.setConfirmed(true);
            message.setConfirmedAt(java.time.LocalDateTime.now());
        }

        return messageRepository.save(message);
    }

    public MessageConfirmationService.MessageConfirmationResult confirmMessage(String messageId, String userId) {
        return messageConfirmationService.confirmMessage(messageId, userId);
    }

    public MessageConfirmationService.MessageConfirmationResult markAsDelivered(String messageId) {
        return messageConfirmationService.markAsDelivered(messageId);
    }

    public MessageRetryService.RetryResult retryMessage(String messageId) {
        return messageRetryService.retryMessage(messageId);
    }

    public java.util.List<MessageRetryService.RetryResult> retryAllPendingMessages() {
        return messageRetryService.retryAllPendingMessages();
    }

    public java.util.List<Message> getPendingConfirmations(String userId) {
        return messageConfirmationService.getPendingConfirmations(userId);
    }

    public long countPendingConfirmations() {
        return messageConfirmationService.countPendingConfirmations();
    }

    public List<Message> getConversation(String userId1, String userId2) {
        return messageRepository.findByFromUserAndToUserOrderBySentAtDesc(userId1, userId2);
    }

    public List<Message> getUserMessages(String userId) {
        return messageRepository.findByFromUserOrToUserOrderBySentAtDesc(userId, userId);
    }

    public List<Message> getUnreadMessages(String userId) {
        return messageRepository.findByToUserAndMessageStatus(userId, "sent");
    }

    public Message getMessageById(String messageId) {
        return messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new SocialNetworkException(404, "消息不存在"));
    }

    public long countTotalMessages() {
        return messageRepository.count();
    }
}
