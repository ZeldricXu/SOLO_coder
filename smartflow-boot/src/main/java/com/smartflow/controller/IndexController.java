package com.smartflow.controller;

import com.smartflow.common.base.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping
public class IndexController {

    @GetMapping
    public Result<Map<String, Object>> index() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "SmartFlow - 智能工单路由分配系统");
        info.put("version", "1.0.0");
        info.put("status", "running");
        info.put("modules", new String[] {
            "ticket-assignment - 工单智能分配",
            "approval-engine - 审批规则引擎",
            "metering-billing - 用量计量与计费",
            "multitenant - 多租户隔离策略",
            "process-designer - 可视化流程设计",
            "skill-graph - 技能图谱建模",
            "document-compare - 文档智能比对",
            "sla-monitor - SLA时效监控"
        });
        return Result.success(info);
    }

    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("OK");
    }
}
