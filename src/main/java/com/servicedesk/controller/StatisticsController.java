package com.servicedesk.controller;

import com.servicedesk.dto.ApiResponse;
import com.servicedesk.entity.Statistic;
import com.servicedesk.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/today")
    public ApiResponse<Statistic> getTodayStatistics() {
        log.info("获取今日统计数据");
        Statistic statistic = statisticsService.getTodayStatistics();
        return ApiResponse.success(statistic);
    }

    @GetMapping("/overall")
    public ApiResponse<Statistic> getOverallStatistics() {
        log.info("获取总体统计数据");
        Statistic statistic = statisticsService.getOverallStatistics();
        return ApiResponse.success(statistic);
    }
}
