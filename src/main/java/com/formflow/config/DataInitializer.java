package com.formflow.config;

import com.formflow.entity.*;
import com.formflow.enums.FieldType;
import com.formflow.enums.NodeType;
import com.formflow.service.FormTemplateService;
import com.formflow.service.ProcessDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Override
    public void run(String... args) {
        try {
            logger.info("开始初始化示例数据...");

            createSampleProcessDefinition();
            createSampleFormTemplate();

            logger.info("示例数据初始化完成");
        } catch (Exception e) {
            logger.warn("示例数据初始化失败: {}", e.getMessage());
        }
    }

    private void createSampleProcessDefinition() {
        if (processDefinitionService.getAllProcessDefinitions().size() > 0) {
            logger.info("流程定义已存在，跳过创建");
            return;
        }

        ProcessDefinition definition = new ProcessDefinition();
        definition.setProcessId("process_leave_approval");
        definition.setProcessName("请假审批流程");
        definition.setDescription("员工请假申请审批流程");
        definition.setStartNodeId("start");
        definition.setEndNodeId("end");
        definition.setVersion(1);
        definition.setEnabled(true);

        List<ProcessNode> nodes = new ArrayList<>();

        ProcessNode startNode = new ProcessNode();
        startNode.setNodeId("start");
        startNode.setNodeName("开始");
        startNode.setNodeType(NodeType.START);
        startNode.setSortOrder(0);
        nodes.add(startNode);

        ProcessNode managerNode = new ProcessNode();
        managerNode.setNodeId("node_manager");
        managerNode.setNodeName("部门经理审批");
        managerNode.setNodeType(NodeType.APPROVAL);
        managerNode.setApproverRole("manager");
        managerNode.setApproverType("role");
        managerNode.setSortOrder(1);
        nodes.add(managerNode);

        ProcessNode hrNode = new ProcessNode();
        hrNode.setNodeId("node_hr");
        hrNode.setNodeName("HR审批");
        hrNode.setNodeType(NodeType.APPROVAL);
        hrNode.setApproverRole("hr");
        hrNode.setApproverType("role");
        hrNode.setSortOrder(2);
        nodes.add(hrNode);

        ProcessNode endNode = new ProcessNode();
        endNode.setNodeId("end");
        endNode.setNodeName("结束");
        endNode.setNodeType(NodeType.END);
        endNode.setSortOrder(3);
        nodes.add(endNode);

        definition.setNodes(nodes);

        List<ProcessTransition> transitions = new ArrayList<>();

        ProcessTransition t1 = new ProcessTransition();
        t1.setFromNode("start");
        t1.setToNode("node_manager");
        t1.setCondition("always");
        t1.setSortOrder(1);
        transitions.add(t1);

        ProcessTransition t2 = new ProcessTransition();
        t2.setFromNode("node_manager");
        t2.setToNode("node_hr");
        t2.setCondition("approved");
        t2.setSortOrder(2);
        transitions.add(t2);

        ProcessTransition t3 = new ProcessTransition();
        t3.setFromNode("node_hr");
        t3.setToNode("end");
        t3.setCondition("approved");
        t3.setSortOrder(3);
        transitions.add(t3);

        definition.setTransitions(transitions);

        processDefinitionService.createProcessDefinition(definition, "system", "系统管理员");
        logger.info("已创建示例流程定义: process_leave_approval");
    }

    private void createSampleFormTemplate() {
        if (formTemplateService.getAllTemplates().size() > 0) {
            logger.info("表单模板已存在，跳过创建");
            return;
        }

        FormTemplate template = new FormTemplate();
        template.setTemplateId("template_leave");
        template.setTemplateName("请假申请表单");
        template.setDescription("员工请假申请表单，包含请假类型、天数、原因等字段");
        template.setProcessDefinitionId("process_leave_approval");
        template.setVersion(1);
        template.setEnabled(true);

        List<FormTemplateField> fields = new ArrayList<>();

        FormTemplateField field1 = new FormTemplateField();
        field1.setFieldId("leave_type");
        field1.setFieldName("请假类型");
        field1.setFieldType(FieldType.SELECT);
        field1.setRequired(true);
        field1.setOptions("[\"事假\",\"病假\",\"年假\",\"婚假\",\"产假\"]");
        field1.setSortOrder(1);
        fields.add(field1);

        FormTemplateField field2 = new FormTemplateField();
        field2.setFieldId("leave_days");
        field2.setFieldName("请假天数");
        field2.setFieldType(FieldType.NUMBER);
        field2.setRequired(true);
        field2.setSortOrder(2);
        fields.add(field2);

        FormTemplateField field3 = new FormTemplateField();
        field3.setFieldId("leave_reason");
        field3.setFieldName("请假原因");
        field3.setFieldType(FieldType.TEXTAREA);
        field3.setRequired(true);
        field3.setPlaceholder("请详细说明请假原因");
        field3.setSortOrder(3);
        fields.add(field3);

        FormTemplateField field4 = new FormTemplateField();
        field4.setFieldId("start_date");
        field4.setFieldName("开始日期");
        field4.setFieldType(FieldType.DATE);
        field4.setRequired(true);
        field4.setSortOrder(4);
        fields.add(field4);

        FormTemplateField field5 = new FormTemplateField();
        field5.setFieldId("end_date");
        field5.setFieldName("结束日期");
        field5.setFieldType(FieldType.DATE);
        field5.setRequired(true);
        field5.setSortOrder(5);
        fields.add(field5);

        template.setFields(fields);

        formTemplateService.createTemplate(template, "system", "系统管理员");
        logger.info("已创建示例表单模板: template_leave");
    }
}
