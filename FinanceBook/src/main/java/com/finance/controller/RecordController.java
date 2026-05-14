package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.dto.RecordCreateRequest;
import com.finance.dto.RecordCreateResponse;
import com.finance.entity.Record;
import com.finance.service.RecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    @PostMapping("/create")
    public ApiResponse<RecordCreateResponse> createRecord(@Valid @RequestBody RecordCreateRequest request) {
        RecordCreateResponse response = recordService.createRecord(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{recordId}")
    public ApiResponse<Record> getRecord(@PathVariable String recordId) {
        Record record = recordService.getRecordById(recordId);
        return ApiResponse.success(record);
    }

    @GetMapping("/account/{accountId}")
    public ApiResponse<List<Record>> getRecordsByAccount(@PathVariable String accountId) {
        List<Record> records = recordService.getRecordsByAccount(accountId);
        return ApiResponse.success(records);
    }
}
