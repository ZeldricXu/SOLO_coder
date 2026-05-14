package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.dto.ExitRequest;
import com.parking.dto.ExitResponse;
import com.parking.entity.ExitRecord;
import com.parking.service.ExitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/exits")
public class ExitController {

    @Autowired
    private ExitService exitService;

    @PostMapping("/process")
    public ApiResponse<ExitResponse> processExit(@RequestBody ExitRequest request) {
        ExitResponse response = exitService.processExit(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{exitId}")
    public ApiResponse<ExitRecord> getExit(@PathVariable String exitId) {
        ExitRecord exit = exitService.getExitById(exitId);
        return ApiResponse.success(exit);
    }

    @GetMapping("/list")
    public ApiResponse<List<ExitRecord>> listExits() {
        List<ExitRecord> exits = exitService.getAllExits();
        return ApiResponse.success(exits);
    }

    @GetMapping("/entry/{entryId}")
    public ApiResponse<ExitRecord> getExitByEntry(@PathVariable String entryId) {
        ExitRecord exit = exitService.getExitByEntryId(entryId);
        return ApiResponse.success(exit);
    }
}
