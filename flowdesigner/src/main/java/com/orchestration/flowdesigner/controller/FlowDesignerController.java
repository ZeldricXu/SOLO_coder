package com.orchestration.flowdesigner.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.flowdesigner.service.FlowDesignerService;
import com.orchestration.persistence.entity.FlowDesign;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/flow-designer")
@RequiredArgsConstructor
public class FlowDesignerController {

    private final FlowDesignerService flowDesignerService;

    @PostMapping("/designs")
    public Result<Long> createDesign(@RequestBody FlowDesign design) {
        return Result.success(flowDesignerService.createDesign(design));
    }

    @PutMapping("/designs/{id}")
    public Result<Boolean> updateDesign(@PathVariable Long id, @RequestBody FlowDesign design) {
        design.setId(id);
        return Result.success(flowDesignerService.updateDesign(design));
    }

    @GetMapping("/designs/{id}")
    public Result<FlowDesign> getDesign(@PathVariable Long id) {
        return Result.success(flowDesignerService.getDesign(id));
    }

    @GetMapping("/designs")
    public Result<List<FlowDesign>> listDesigns(
            @RequestParam(required = false) String flowType,
            @RequestParam(required = false) String status) {
        return Result.success(flowDesignerService.listDesigns(flowType, status));
    }

    @DeleteMapping("/designs/{id}")
    public Result<Boolean> deleteDesign(@PathVariable Long id) {
        return Result.success(flowDesignerService.deleteDesign(id));
    }

    @PostMapping("/designs/{id}/publish")
    public Result<Boolean> publishDesign(@PathVariable Long id) {
        return Result.success(flowDesignerService.publishDesign(id));
    }

    @PostMapping("/designs/validate")
    public Result<Map<String, Object>> validateDesign(@RequestBody Map<String, Object> designData) {
        return Result.success(flowDesignerService.validateDesign(designData));
    }

    @PostMapping("/nodes/validate")
    public Result<Map<String, Object>> validateNode(@RequestBody Map<String, Object> node) {
        return Result.success(flowDesignerService.validateNode(node));
    }

    @PostMapping("/edges/validate")
    public Result<Map<String, Object>> validateEdge(
            @RequestBody Map<String, Object> edge,
            @RequestParam List<Map<String, Object>> nodes) {
        return Result.success(flowDesignerService.validateEdge(edge, nodes));
    }

    @GetMapping("/designs/{id}/flow-definition")
    public Result<Map<String, Object>> generateFlowDefinition(@PathVariable Long id) {
        return Result.success(flowDesignerService.generateFlowDefinition(id));
    }

    @GetMapping("/designs/{id}/preview")
    public Result<Map<String, Object>> getDesignPreview(@PathVariable Long id) {
        return Result.success(flowDesignerService.getDesignPreview(id));
    }

    @PostMapping("/designs/{id}/copy")
    public Result<Boolean> copyDesign(
            @PathVariable Long id,
            @RequestParam String newDesignCode,
            @RequestParam String newDesignName) {
        return Result.success(flowDesignerService.copyDesign(id, newDesignCode, newDesignName));
    }

    @GetMapping("/node-templates")
    public Result<List<Map<String, Object>>> getNodeTemplates() {
        return Result.success(flowDesignerService.getNodeTemplates());
    }
}
