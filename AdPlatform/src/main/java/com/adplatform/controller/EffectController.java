package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.dto.EffectEvent;
import com.adplatform.dto.EffectQueryRequest;
import com.adplatform.dto.EffectQueryResponse;
import com.adplatform.entity.AdEffect;
import com.adplatform.service.EffectService;
import com.adplatform.service.StatisticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ads")
public class EffectController {
    private final StatisticsService statisticsService;
    private final EffectService effectService;

    public EffectController(StatisticsService statisticsService,
                           EffectService effectService) {
        this.statisticsService = statisticsService;
        this.effectService = effectService;
    }

    @GetMapping("/effects")
    public ApiResponse<EffectQueryResponse> queryEffects(
            @RequestParam String adId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        EffectQueryRequest request = EffectQueryRequest.builder()
                .adId(adId)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        EffectQueryResponse response = statisticsService.queryEffects(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/effects/event")
    public ApiResponse<Void> submitEffectEvent(@RequestBody EffectEvent event) {
        effectService.submitEffectEvent(event);
        return ApiResponse.success(null);
    }

    @GetMapping("/{adId}/effects/details")
    public ApiResponse<List<AdEffect>> getEffectDetails(
            @PathVariable String adId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<AdEffect> effects = statisticsService.getEffectDetails(adId, startDate, endDate);
        return ApiResponse.success(effects);
    }
}
