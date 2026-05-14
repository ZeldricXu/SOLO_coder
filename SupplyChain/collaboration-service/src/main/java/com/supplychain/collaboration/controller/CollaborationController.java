package com.supplychain.collaboration.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.SupplierMessage;
import com.supplychain.collaboration.service.CollaborationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "供应商协同", description = "供应商协同沟通管理接口")
@RestController
@RequestMapping("/api/collaboration")
@RequiredArgsConstructor
public class CollaborationController {

    private final CollaborationService collaborationService;

    @Operation(summary = "发送消息")
    @PostMapping("/messages")
    public ResponseResult<SupplierMessage> sendMessage(@RequestBody SupplierMessage message) {
        return ResponseResult.success(collaborationService.sendMessage(message));
    }

    @Operation(summary = "获取供应商消息")
    @GetMapping("/messages/supplier/{supplierId}")
    public ResponseResult<List<SupplierMessage>> getMessagesBySupplier(@PathVariable String supplierId) {
        return ResponseResult.success(collaborationService.getMessagesBySupplier(supplierId));
    }

    @Operation(summary = "获取订单相关消息")
    @GetMapping("/messages/order/{orderId}")
    public ResponseResult<List<SupplierMessage>> getMessagesByOrder(@PathVariable String orderId) {
        return ResponseResult.success(collaborationService.getMessagesByOrder(orderId));
    }

    @Operation(summary = "标记消息已读")
    @PostMapping("/messages/{messageId}/read")
    public ResponseResult<SupplierMessage> markAsRead(@PathVariable String messageId) {
        return ResponseResult.success(collaborationService.markAsRead(messageId));
    }

    @Operation(summary = "获取未读消息")
    @GetMapping("/messages/unread")
    public ResponseResult<List<SupplierMessage>> getUnreadMessages(@RequestParam String receiver) {
        return ResponseResult.success(collaborationService.getUnreadMessages(receiver));
    }

    @Operation(summary = "获取未读消息数")
    @GetMapping("/messages/unread/count")
    public ResponseResult<Map<String, Long>> getUnreadCount(@RequestParam String receiver) {
        return ResponseResult.success(Map.of("count", collaborationService.getUnreadCount(receiver)));
    }
}
