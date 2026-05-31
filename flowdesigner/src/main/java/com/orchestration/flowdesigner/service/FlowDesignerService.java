package com.orchestration.flowdesigner.service;

import com.orchestration.persistence.entity.FlowDesign;
import java.util.List;
import java.util.Map;

public interface FlowDesignerService {

    Long createDesign(FlowDesign design);

    boolean updateDesign(FlowDesign design);

    FlowDesign getDesign(Long id);

    List<FlowDesign> listDesigns(String flowType, String status);

    boolean deleteDesign(Long id);

    boolean publishDesign(Long id);

    boolean validateDesign(Map<String, Object> designData);

    Map<String, Object> validateNode(Map<String, Object> node);

    Map<String, Object> validateEdge(Map<String, Object> edge, List<Map<String, Object>> nodes);

    Map<String, Object> generateFlowDefinition(Long designId);

    Map<String, Object> getDesignPreview(Long id);

    boolean copyDesign(Long id, String newDesignCode, String newDesignName);

    List<Map<String, Object>> getNodeTemplates();
}
