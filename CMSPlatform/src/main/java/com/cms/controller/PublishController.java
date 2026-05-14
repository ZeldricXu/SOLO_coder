package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.dto.PublishExecuteRequest;
import com.cms.entity.PublishRecord;
import com.cms.service.PublishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/publishes")
public class PublishController {

    @Autowired
    private PublishService publishService;

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> executePublish(@Valid @RequestBody PublishExecuteRequest request) {
        PublishRecord publishRecord = publishService.executePublish(request);

        Map<String, Object> result = new HashMap<>();
        result.put("publish_id", publishRecord.getPublishId());
        result.put("content_id", publishRecord.getContentId());
        result.put("status", publishRecord.getPublishStatus());
        result.put("publish_channel", publishRecord.getPublishChannel());
        result.put("publish_time", publishRecord.getPublishTime());

        return ApiResponse.success(result);
    }

    @PostMapping("/{contentId}/unpublish")
    public ApiResponse<PublishRecord> unpublishContent(
            @PathVariable String contentId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String operatorName) {
        PublishRecord publishRecord = publishService.unpublishContent(contentId, operatorId, operatorName);
        return ApiResponse.success(publishRecord);
    }

    @GetMapping("/{publishId}")
    public ApiResponse<PublishRecord> getPublish(@PathVariable String publishId) {
        PublishRecord publishRecord = publishService.getPublishById(publishId);
        return ApiResponse.success(publishRecord);
    }

    @GetMapping
    public ApiResponse<List<PublishRecord>> getAllPublishes() {
        List<PublishRecord> publishes = publishService.getAllPublishes();
        return ApiResponse.success(publishes);
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<List<PublishRecord>> getPublishesByContent(@PathVariable String contentId) {
        List<PublishRecord> publishes = publishService.getPublishesByContentId(contentId);
        return ApiResponse.success(publishes);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<PublishRecord>> getPublishesByStatus(@PathVariable String status) {
        List<PublishRecord> publishes = publishService.getPublishesByStatus(status);
        return ApiResponse.success(publishes);
    }

    @GetMapping("/channel/{channel}")
    public ApiResponse<List<PublishRecord>> getPublishesByChannel(@PathVariable String channel) {
        List<PublishRecord> publishes = publishService.getPublishesByChannel(channel);
        return ApiResponse.success(publishes);
    }
}
