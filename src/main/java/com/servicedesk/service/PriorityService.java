package com.servicedesk.service;

import com.servicedesk.dto.CreateTicketRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class PriorityService {

    private static final List<String> HIGH_PRIORITY_KEYWORDS = Arrays.asList(
            "紧急", "urgent", "critical", "无法使用", "系统崩溃", "宕机", "down", "block", "阻塞", "生产"
    );

    private static final List<String> MEDIUM_PRIORITY_KEYWORDS = Arrays.asList(
            "重要", "important", "影响使用", "bug", "错误", "问题", "issue", "故障"
    );

    public String evaluatePriority(CreateTicketRequest request) {
        if (request.getTicketPriority() != null && !request.getTicketPriority().isEmpty()) {
            String providedPriority = request.getTicketPriority().toLowerCase();
            if (Arrays.asList("high", "medium", "low", "critical", "normal").contains(providedPriority)) {
                log.info("使用用户指定的优先级: {}", providedPriority);
                return providedPriority;
            }
        }

        String title = request.getTicketTitle().toLowerCase();
        String content = request.getTicketContent().toLowerCase();
        String combinedText = title + " " + content;

        for (String keyword : HIGH_PRIORITY_KEYWORDS) {
            if (combinedText.contains(keyword.toLowerCase())) {
                log.info("根据关键词 '{}' 评估为高优先级", keyword);
                return "high";
            }
        }

        for (String keyword : MEDIUM_PRIORITY_KEYWORDS) {
            if (combinedText.contains(keyword.toLowerCase())) {
                log.info("根据关键词 '{}' 评估为中优先级", keyword);
                return "medium";
            }
        }

        log.info("评估为低优先级");
        return "low";
    }

    public String determineCategory(String category) {
        if (category == null || category.isEmpty()) {
            return "general";
        }
        String normalizedCategory = category.toLowerCase();
        if (Arrays.asList("technical", "业务", "billing", "业务咨询", "功能请求", "general").contains(normalizedCategory)) {
            return normalizedCategory;
        }
        if (normalizedCategory.contains("技术") || normalizedCategory.contains("system") || normalizedCategory.contains("bug")) {
            return "technical";
        }
        if (normalizedCategory.contains("业务") || normalizedCategory.contains("咨询")) {
            return "业务咨询";
        }
        return "general";
    }
}
