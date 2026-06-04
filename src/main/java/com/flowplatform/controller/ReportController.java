package com.flowplatform.controller;

import com.flowplatform.entity.FormDefinition;
import com.flowplatform.service.FormDefinitionService;
import com.flowplatform.service.ProcessInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/report")
public class ReportController {

    private final ProcessInstanceService processInstanceService;
    private final FormDefinitionService formDefinitionService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("forms", formDefinitionService.list());
        return "report/index";
    }

    @GetMapping("/form/{formId}")
    public String formDetail(@PathVariable Long formId, Model model) {
        FormDefinition form = formDefinitionService.getById(formId);
        model.addAttribute("form", form);
        return "report/form-detail";
    }

    @GetMapping("/api/status-stats")
    @ResponseBody
    public List<Map<String, Object>> statusStats() {
        return processInstanceService.getStatusStats();
    }

    @GetMapping("/api/date-trend")
    @ResponseBody
    public List<Map<String, Object>> dateTrend() {
        return processInstanceService.getDateTrend();
    }

    @GetMapping("/api/avg-time")
    @ResponseBody
    public Map<String, Object> avgTime() {
        return processInstanceService.getAvgApprovalTime();
    }

    @GetMapping("/api/node-time")
    @ResponseBody
    public List<Map<String, Object>> nodeTime() {
        return processInstanceService.getNodeAvgTime();
    }

    @GetMapping("/api/form-ranking")
    @ResponseBody
    public List<Map<String, Object>> formRanking() {
        return processInstanceService.getFormRanking();
    }
}
