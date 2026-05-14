package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.entity.MeetingType;
import com.meeting.service.MeetingTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/types")
@RequiredArgsConstructor
public class TypeController {

    private final MeetingTypeService typeService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MeetingType>>> getAllTypes() {
        List<MeetingType> types = typeService.getAllTypes();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<MeetingType>>> getActiveTypes() {
        List<MeetingType> types = typeService.getActiveTypes();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/{typeId}")
    public ResponseEntity<ApiResponse<MeetingType>> getTypeById(@PathVariable String typeId) {
        MeetingType type = typeService.getTypeById(typeId);
        return ResponseEntity.ok(ApiResponse.success(type));
    }

    @GetMapping("/code/{typeCode}")
    public ResponseEntity<ApiResponse<MeetingType>> getTypeByCode(@PathVariable String typeCode) {
        MeetingType type = typeService.getTypeByCode(typeCode);
        return ResponseEntity.ok(ApiResponse.success(type));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MeetingType>> createType(@RequestBody MeetingType type) {
        MeetingType created = typeService.createType(type);
        return ResponseEntity.ok(ApiResponse.success("会议类型创建成功", created));
    }

    @PutMapping("/{typeId}")
    public ResponseEntity<ApiResponse<MeetingType>> updateType(
            @PathVariable String typeId,
            @RequestBody MeetingType typeUpdate) {
        MeetingType updated = typeService.updateType(typeId, typeUpdate);
        return ResponseEntity.ok(ApiResponse.success("会议类型更新成功", updated));
    }

    @DeleteMapping("/{typeId}")
    public ResponseEntity<ApiResponse<Void>> deleteType(@PathVariable String typeId) {
        typeService.deleteType(typeId);
        return ResponseEntity.ok(ApiResponse.success("会议类型删除成功", null));
    }

    @PostMapping("/{typeId}/activate")
    public ResponseEntity<ApiResponse<MeetingType>> activateType(@PathVariable String typeId) {
        MeetingType type = typeService.activateType(typeId);
        return ResponseEntity.ok(ApiResponse.success("会议类型已激活", type));
    }

    @PostMapping("/{typeId}/deactivate")
    public ResponseEntity<ApiResponse<MeetingType>> deactivateType(@PathVariable String typeId) {
        MeetingType type = typeService.deactivateType(typeId);
        return ResponseEntity.ok(ApiResponse.success("会议类型已停用", type));
    }
}
