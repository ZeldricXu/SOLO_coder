package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.dto.TrackQueryResponse;
import com.logistics.service.TrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tracks")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;

    @GetMapping("/query")
    public ApiResponse<TrackQueryResponse> getTracksByLogisticsNumber(
            @RequestParam(name = "logistics_number") String logisticsNumber) {
        TrackQueryResponse response = trackService.getTracksByLogisticsNumber(logisticsNumber);
        return ApiResponse.success(response);
    }

    @GetMapping("/logistics/{logisticsId}")
    public ApiResponse<TrackQueryResponse> getTracksByLogisticsId(@PathVariable String logisticsId) {
        TrackQueryResponse response = trackService.getTracksByLogisticsId(logisticsId);
        return ApiResponse.success(response);
    }
}
