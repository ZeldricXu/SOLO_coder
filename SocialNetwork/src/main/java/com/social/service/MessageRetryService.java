package com.social.service;

import com.social.entity.Message;
import com.social.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class MessageRetryService {

    @Autowired
    private MessageRepository messageRepository;

    public static class RetryResult {
        private String messageId;
        private int previousRetryCount;
        private int newRetryCount;
        private boolean retryAttempted;
        private boolean maxRetriesReached;
        private String status;

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public int getPreviousRetryCount() {
            return previousRetryCount;
        }

        public void setPreviousRetryCount(int previousRetryCount) {
            this.previousRetryCount = previousRetryCount;
        }

        public int getNewRetryCount() {
            return newRetryCount;
        }

        public void setNewRetryCount(int newRetryCount) {
            this.newRetryCount = newRetryCount;
        }

        public boolean isRetryAttempted() {
            return retryAttempted;
        }

        public void setRetryAttempted(boolean retryAttempted) {
            this.retryAttempted = retryAttempted;
        }

        public boolean isMaxRetriesReached() {
            return maxRetriesReached;
        }

        public void setMaxRetriesReached(boolean maxRetriesReached) {
            this.maxRetriesReached = maxRetriesReached;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    @Transactional
    public RetryResult retryMessage(String messageId) {
        RetryResult result = new RetryResult();
        
        Message message = messageRepository.findByMessageId(messageId).orElse(null);
        
        if (message == null) {
            result.setRetryAttempted(false);
            result.setStatus("message_not_found");
            return result;
        }

        if (message.isConfirmed()) {
            result.setRetryAttempted(false);
            result.setStatus("already_confirmed");
            return result;
        }

        result.setMessageId(messageId);
        result.setPreviousRetryCount(message.getRetryCount());

        if (message.getRetryCount() >= message.getMaxRetryCount()) {
            message.setMessageStatus("failed");
            messageRepository.save(message);
            
            result.setMaxRetriesReached(true);
            result.setRetryAttempted(false);
            result.setStatus("max_retries_reached");
            return result;
        }

        message.setRetryCount(message.getRetryCount() + 1);
        message.setLastRetryAt(LocalDateTime.now());
        message.setMessageStatus("retrying");
        messageRepository.save(message);

        result.setNewRetryCount(message.getRetryCount());
        result.setRetryAttempted(true);
        result.setMaxRetriesReached(false);
        result.setStatus("retry_scheduled");

        return result;
    }

    @Transactional
    public List<RetryResult> retryAllPendingMessages() {
        List<String> statuses = Arrays.asList("sent", "retrying");
        List<Message> pendingMessages = messageRepository
                .findByNeedsConfirmationTrueAndConfirmedFalseAndMessageStatusInOrderBySentAtAsc(statuses);
        
        List<RetryResult> results = new java.util.ArrayList<>();
        for (Message message : pendingMessages) {
            RetryResult result = retryMessage(message.getMessageId());
            results.add(result);
        }
        
        return results;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    public void scheduledRetryMessages() {
        retryAllPendingMessages();
    }

    @Transactional
    public Message setMessageNeedsConfirmation(String messageId, boolean needsConfirmation, int maxRetryCount) {
        Message message = messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new com.social.exception.SocialNetworkException(404, "消息不存在"));
        
        message.setNeedsConfirmation(needsConfirmation);
        if (maxRetryCount > 0) {
            message.setMaxRetryCount(maxRetryCount);
        }
        
        return messageRepository.save(message);
    }

    @Transactional
    public Message updateMaxRetryCount(String messageId, int maxRetryCount) {
        Message message = messageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new com.social.exception.SocialNetworkException(404, "消息不存在"));
        
        message.setMaxRetryCount(maxRetryCount);
        return messageRepository.save(message);
    }

    public long countPendingRetryMessages() {
        return messageRepository.countByNeedsConfirmationTrueAndConfirmedFalse();
    }

    public List<Message> getPendingRetryMessages() {
        List<String> statuses = Arrays.asList("sent", "retrying");
        return messageRepository
                .findByNeedsConfirmationTrueAndConfirmedFalseAndMessageStatusInOrderBySentAtAsc(statuses);
    }
}
