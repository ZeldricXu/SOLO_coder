package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.Message;
import com.social.service.MessageConfirmationService;
import com.social.service.MessageRetryService;
import com.social.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    @Autowired
    private MessageService messageService;

    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> request) {
        String fromUser = (String) request.get("from_user");
        String toUser = (String) request.get("to_user");
        String messageContent = (String) request.get("message_content");
        Boolean needsConfirmation = request.get("needs_confirmation") != null 
                ? (Boolean) request.get("needs_confirmation") : false;
        Integer maxRetryCount = request.get("max_retry_count") != null 
                ? ((Number) request.get("max_retry_count")).intValue() : 3;

        Message message = messageService.sendMessage(fromUser, toUser, messageContent, needsConfirmation, maxRetryCount);
        
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", message.getMessageId());
        data.put("status", message.getMessageStatus());
        data.put("needs_confirmation", message.isNeedsConfirmation());
        data.put("retry_count", message.getRetryCount());
        data.put("max_retry_count", message.getMaxRetryCount());

        return ApiResponse.success(data);
    }

    @GetMapping("/{messageId}")
    public ApiResponse<Message> getMessage(@PathVariable String messageId) {
        Message message = messageService.getMessageById(messageId);
        return ApiResponse.success(message);
    }

    @GetMapping("/conversation/{userId1}/{userId2}")
    public ApiResponse<List<Message>> getConversation(@PathVariable String userId1, @PathVariable String userId2) {
        List<Message> messages = messageService.getConversation(userId1, userId2);
        return ApiResponse.success(messages);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Message>> getUserMessages(@PathVariable String userId) {
        List<Message> messages = messageService.getUserMessages(userId);
        return ApiResponse.success(messages);
    }

    @GetMapping("/unread/{userId}")
    public ApiResponse<List<Message>> getUnreadMessages(@PathVariable String userId) {
        List<Message> messages = messageService.getUnreadMessages(userId);
        return ApiResponse.success(messages);
    }

    @PutMapping("/{messageId}/read")
    public ApiResponse<Message> markAsRead(@PathVariable String messageId, @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        Message message = messageService.markAsRead(messageId, userId);
        return ApiResponse.success(message);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> getTotalMessageCount() {
        Map<String, Object> result = new HashMap<>();
        result.put("total_messages", messageService.countTotalMessages());
        return ApiResponse.success(result);
    }

    @PostMapping("/{messageId}/confirm")
    public ApiResponse<MessageConfirmationService.MessageConfirmationResult> confirmMessage(
            @PathVariable String messageId, 
            @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        MessageConfirmationService.MessageConfirmationResult result = 
                messageService.confirmMessage(messageId, userId);
        return ApiResponse.success(result);
    }

    @PostMapping("/{messageId}/delivered")
    public ApiResponse<MessageConfirmationService.MessageConfirmationResult> markAsDelivered(
            @PathVariable String messageId) {
        MessageConfirmationService.MessageConfirmationResult result = 
                messageService.markAsDelivered(messageId);
        return ApiResponse.success(result);
    }

    @PostMapping("/{messageId}/retry")
    public ApiResponse<MessageRetryService.RetryResult> retryMessage(
            @PathVariable String messageId) {
        MessageRetryService.RetryResult result = messageService.retryMessage(messageId);
        return ApiResponse.success(result);
    }

    @PostMapping("/retry-all")
    public ApiResponse<List<MessageRetryService.RetryResult>> retryAllPendingMessages() {
        List<MessageRetryService.RetryResult> results = messageService.retryAllPendingMessages();
        return ApiResponse.success(results);
    }

    @GetMapping("/pending-confirmations/{userId}")
    public ApiResponse<List<Message>> getPendingConfirmations(@PathVariable String userId) {
        List<Message> messages = messageService.getPendingConfirmations(userId);
        return ApiResponse.success(messages);
    }

    @GetMapping("/pending-confirmations/count")
    public ApiResponse<Map<String, Object>> countPendingConfirmations() {
        Map<String, Object> result = new HashMap<>();
        result.put("pending_confirmations", messageService.countPendingConfirmations());
        return ApiResponse.success(result);
    }
}
