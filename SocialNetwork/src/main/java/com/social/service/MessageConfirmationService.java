package com.social.service;

import com.social.entity.Message;
import com.social.exception.SocialNetworkException;
import com.social.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageConfirmationService {

    @Autowired
    private MessageRepository messageRepository;

    public static class MessageConfirmationResult {
        private boolean success;
        private String message;
        private String messageId;
        private String newStatus;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getNewStatus() {
            return newStatus;
        }

        public void setNewStatus(String newStatus) {
            this.newStatus = newStatus;
        }
    }

    @Transactional
    public MessageConfirmationResult confirmMessage(String messageId, String confirmerUserId) {
        MessageConfirmationResult result = new MessageConfirmationResult();
        
        Message message = messageRepository.findByMessageId(messageId)
                .orElse(null);
        
        if (message == null) {
            result.setSuccess(false);
            result.setMessage("消息不存在");
            return result;
        }

        if (!message.getToUser().equals(confirmerUserId)) {
            result.setSuccess(false);
            result.setMessage("无权确认此消息");
            return result;
        }

        if (message.isConfirmed()) {
            result.setSuccess(true);
            result.setMessage("消息已确认");
            result.setMessageId(messageId);
            result.setNewStatus(message.getMessageStatus());
            return result;
        }

        if (!message.isNeedsConfirmation()) {
            message.setConfirmed(true);
            message.setConfirmedAt(LocalDateTime.now());
            messageRepository.save(message);
            
            result.setSuccess(true);
            result.setMessage("消息确认成功（无需确认模式）");
            result.setMessageId(messageId);
            result.setNewStatus(message.getMessageStatus());
            return result;
        }

        message.setConfirmed(true);
        message.setConfirmedAt(LocalDateTime.now());
        message.setMessageStatus("confirmed");
        
        Message savedMessage = messageRepository.save(message);
        
        result.setSuccess(true);
        result.setMessage("消息确认成功");
        result.setMessageId(messageId);
        result.setNewStatus(savedMessage.getMessageStatus());
        
        return result;
    }

    @Transactional
    public MessageConfirmationResult markAsDelivered(String messageId) {
        MessageConfirmationResult result = new MessageConfirmationResult();
        
        Message message = messageRepository.findByMessageId(messageId)
                .orElse(null);
        
        if (message == null) {
            result.setSuccess(false);
            result.setMessage("消息不存在");
            return result;
        }

        if ("delivered".equals(message.getMessageStatus()) || 
            "confirmed".equals(message.getMessageStatus()) || 
            "read".equals(message.getMessageStatus())) {
            result.setSuccess(true);
            result.setMessage("消息已送达");
            result.setMessageId(messageId);
            result.setNewStatus(message.getMessageStatus());
            return result;
        }

        message.setMessageStatus("delivered");
        message.setDeliveredAt(LocalDateTime.now());
        messageRepository.save(message);
        
        result.setSuccess(true);
        result.setMessage("消息标记为已送达");
        result.setMessageId(messageId);
        result.setNewStatus("delivered");
        
        return result;
    }

    public List<Message> getPendingConfirmations(String userId) {
        List<String> statuses = java.util.Arrays.asList("sent", "delivered");
        return messageRepository.findByToUserAndNeedsConfirmationTrueAndConfirmedFalseOrderBySentAtDesc(userId);
    }

    public long countPendingConfirmations() {
        return messageRepository.countByNeedsConfirmationTrueAndConfirmedFalse();
    }

    public long countPendingConfirmations(String userId) {
        return getPendingConfirmations(userId).size();
    }

    public boolean needsConfirmation(String messageId) {
        Message message = messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new SocialNetworkException(404, "消息不存在"));
        return message.isNeedsConfirmation();
    }

    public boolean isConfirmed(String messageId) {
        Message message = messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new SocialNetworkException(404, "消息不存在"));
        return message.isConfirmed();
    }
}
