package com.datastandard.modules.notification.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.common.model.NotificationRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Validated
@Tag(name = "通知管理", description = "通知发送和管理API")
public class NotificationController {

    @PostMapping("/send")
    @Operation(summary = "发送通知", description = "发送单个通知")
    @PreAuthorize("hasAuthority('notification:send')")
    public Mono<ResponseEntity<ApiResponse<NotificationRecord>>> sendNotification(
            @Valid @RequestBody NotificationRecord request) {
        log.info("发送通知: type={}, target={}", request.getNotificationType(), request.getTarget());
        request.setNotificationId(UUID.randomUUID().toString());
        request.setStatus("SENT");
        request.setSentAt(Instant.now());
        request.setCreatedAt(Instant.now());
        request.setDeleted(0);
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("通知发送成功", request)));
    }

    @PostMapping("/send/batch")
    @Operation(summary = "批量发送通知", description = "批量发送多个通知")
    @PreAuthorize("hasAuthority('notification:send')")
    public Mono<ResponseEntity<ApiResponse<List<NotificationRecord>>>> batchSendNotifications(
            @Valid @RequestBody List<NotificationRecord> requests) {
        log.info("批量发送通知: count={}", requests.size());
        List<NotificationRecord> results = new ArrayList<>();
        for (NotificationRecord request : requests) {
            request.setNotificationId(UUID.randomUUID().toString());
            request.setStatus("SENT");
            request.setSentAt(Instant.now());
            request.setCreatedAt(Instant.now());
            request.setDeleted(0);
            results.add(request);
        }
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("批量通知发送成功", results)));
    }

    @GetMapping("/{notificationId}")
    @Operation(summary = "查询通知详情", description = "根据ID查询通知详情")
    @PreAuthorize("hasAuthority('notification:read')")
    public Mono<ResponseEntity<ApiResponse<NotificationRecord>>> getNotification(
            @Parameter(description = "通知ID") @PathVariable @NotBlank(message = "通知ID不能为空") String notificationId) {
        log.info("查询通知详情: notificationId={}", notificationId);
        NotificationRecord record = NotificationRecord.builder()
                .notificationId(notificationId)
                .notificationType("EMAIL")
                .target("user@example.com")
                .subject("系统通知")
                .content("这是一条测试通知")
                .status("SENT")
                .sentAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(record)));
    }

    @GetMapping
    @Operation(summary = "查询通知列表", description = "分页查询通知记录")
    @PreAuthorize("hasAuthority('notification:read')")
    public Mono<ResponseEntity<ApiResponse<List<NotificationRecord>>>> getNotifications(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "通知类型") @RequestParam(required = false) String notificationType,
            @Parameter(description = "状态") @RequestParam(required = false) String status) {
        log.info("查询通知列表: pageNum={}, pageSize={}, type={}", pageNum, pageSize, notificationType);
        List<NotificationRecord> records = List.of(
                NotificationRecord.builder()
                        .notificationId(UUID.randomUUID().toString())
                        .notificationType("EMAIL")
                        .target("admin@example.com")
                        .subject("系统告警")
                        .status("SENT")
                        .sentAt(Instant.now())
                        .build(),
                NotificationRecord.builder()
                        .notificationId(UUID.randomUUID().toString())
                        .notificationType("SMS")
                        .target("13800138000")
                        .subject("验证码")
                        .status("SENT")
                        .sentAt(Instant.now())
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(records)));
    }

    @PutMapping("/{notificationId}/status")
    @Operation(summary = "更新通知状态", description = "更新通知的阅读状态")
    @PreAuthorize("hasAuthority('notification:update')")
    public Mono<ResponseEntity<ApiResponse<NotificationRecord>>> updateNotificationStatus(
            @Parameter(description = "通知ID") @PathVariable @NotBlank(message = "通知ID不能为空") String notificationId,
            @RequestBody Map<String, String> statusUpdate) {
        log.info("更新通知状态: notificationId={}, status={}", notificationId, statusUpdate.get("status"));
        NotificationRecord record = NotificationRecord.builder()
                .notificationId(notificationId)
                .status(statusUpdate.get("status"))
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success("通知状态更新成功", record)));
    }

    @DeleteMapping("/{notificationId}")
    @Operation(summary = "删除通知", description = "删除指定的通知记录")
    @PreAuthorize("hasAuthority('notification:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteNotification(
            @Parameter(description = "通知ID") @PathVariable @NotBlank(message = "通知ID不能为空") String notificationId) {
        log.info("删除通知: notificationId={}", notificationId);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("通知删除成功", null)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行通知操作")
    @PreAuthorize("hasAuthority('notification:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<NotificationRecord> request) {
        log.info("批量操作通知: operationType={}", request.getOperationType());

        List<Long> successIds = new ArrayList<>();
        List<BatchOperationResult.FailedItem> failedItems = new ArrayList<>();

        if (request.getIds() != null) {
            for (Long id : request.getIds()) {
                try {
                    successIds.add(id);
                } catch (Exception e) {
                    failedItems.add(BatchOperationResult.FailedItem.builder()
                            .id(id)
                            .errorCode("OPERATION_FAILED")
                            .errorMessage(e.getMessage())
                            .build());
                }
            }
        }

        BatchOperationResult result = BatchOperationResult.builder()
                .totalCount(request.getIds() != null ? request.getIds().size() : 0)
                .successCount(successIds.size())
                .failedCount(failedItems.size())
                .successIds(successIds)
                .failedItems(failedItems)
                .build();

        return Mono.just(ResponseEntity.ok(ApiResponse.success("批量操作完成", result)));
    }
}
