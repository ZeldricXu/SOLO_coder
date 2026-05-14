package com.formflow.service;

import com.formflow.builder.TestDataBuilder;
import com.formflow.entity.ProcessDefinition;
import com.formflow.entity.ProcessNode;
import com.formflow.entity.ProcessTransition;
import com.formflow.enums.NodeType;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ProcessDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("流程定义服务测试")
class ProcessDefinitionServiceTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;

    @InjectMocks
    private ProcessDefinitionService processDefinitionService;

    private ProcessDefinition simpleProcess;
    private ProcessDefinition conditionalProcess;
    private ProcessDefinition nestedConditionalProcess;

    @BeforeEach
    void setUp() {
        simpleProcess = TestDataBuilder.buildBasicProcessDefinition();
        conditionalProcess = TestDataBuilder.buildConditionalProcess();
        nestedConditionalProcess = TestDataBuilder.buildNestedConditionalProcess();
    }

    @Test
    @DisplayName("测试获取流程定义 - 成功")
    void testGetProcessDefinition_Success() {
        when(processDefinitionRepository.findByProcessId(simpleProcess.getProcessId()))
                .thenReturn(Optional.of(simpleProcess));

        ProcessDefinition result = processDefinitionService.getProcessDefinition(simpleProcess.getProcessId());

        assertNotNull(result);
        assertEquals(simpleProcess.getProcessId(), result.getProcessId());
        assertEquals(simpleProcess.getProcessName(), result.getProcessName());
    }

    @Test
    @DisplayName("测试获取流程定义 - 失败抛出异常")
    void testGetProcessDefinition_NotFound() {
        String nonExistentId = "process_nonexistent";
        when(processDefinitionRepository.findByProcessId(nonExistentId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> processDefinitionService.getProcessDefinition(nonExistentId));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("流程定义不存在"));
    }

    @Test
    @DisplayName("测试创建流程定义 - 成功")
    void testCreateProcessDefinition_Success() {
        ProcessDefinition newProcess = TestDataBuilder.buildBasicProcessDefinition();
        when(processDefinitionRepository.existsByProcessId(newProcess.getProcessId()))
                .thenReturn(false);
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenReturn(newProcess);

        ProcessDefinition result = processDefinitionService.createProcessDefinition(
                newProcess, "test_creator", "测试创建者");

        assertNotNull(result);
        assertEquals(newProcess.getProcessId(), result.getProcessId());
        verify(processDefinitionRepository, times(1)).save(any(ProcessDefinition.class));
    }

    @Test
    @DisplayName("测试创建流程定义 - ID重复抛出异常")
    void testCreateProcessDefinition_DuplicateId() {
        ProcessDefinition existingProcess = simpleProcess;
        when(processDefinitionRepository.existsByProcessId(existingProcess.getProcessId()))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> processDefinitionService.createProcessDefinition(existingProcess, "test_creator", "测试创建者"));

        assertTrue(exception.getMessage().contains("流程ID已存在"));
        verify(processDefinitionRepository, never()).save(any(ProcessDefinition.class));
    }

    @Test
    @DisplayName("测试创建流程定义 - 缺少开始节点抛出异常")
    void testCreateProcessDefinition_MissingStartNode() {
        ProcessDefinition invalidProcess = TestDataBuilder.buildBasicProcessDefinition();
        invalidProcess.setStartNodeId("nonexistent_node");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> processDefinitionService.createProcessDefinition(invalidProcess, "test_creator", "测试创建者"));

        assertTrue(exception.getMessage().contains("开始节点不存在"));
    }

    @Test
    @DisplayName("测试获取节点 - 成功")
    void testGetNodeById_Success() {
        ProcessNode approvalNode = simpleProcess.getNodes().stream()
                .filter(n -> "node_approval".equals(n.getNodeId()))
                .findFirst()
                .orElse(null);

        assertNotNull(approvalNode);

        ProcessNode result = processDefinitionService.getNodeById(simpleProcess, "node_approval");

        assertNotNull(result);
        assertEquals("node_approval", result.getNodeId());
        assertEquals("审批节点", result.getNodeName());
        assertEquals(NodeType.APPROVAL, result.getNodeType());
    }

    @Test
    @DisplayName("测试获取节点 - 不存在返回null")
    void testGetNodeById_NotFound() {
        ProcessNode result = processDefinitionService.getNodeById(simpleProcess, "nonexistent_node");

        assertNull(result);
    }

    @Test
    @DisplayName("测试是否结束节点")
    void testIsEndNode() {
        assertTrue(processDefinitionService.isEndNode(simpleProcess, "end"));
        assertFalse(processDefinitionService.isEndNode(simpleProcess, "node_approval"));
        assertFalse(processDefinitionService.isEndNode(simpleProcess, "start"));
    }

    @Test
    @DisplayName("测试获取节点流转规则")
    void testGetTransitionsFromNode() {
        List<ProcessTransition> transitions = processDefinitionService.getTransitionsFromNode(
                simpleProcess, "start");

        assertNotNull(transitions);
        assertEquals(1, transitions.size());
        assertEquals("node_approval", transitions.get(0).getToNode());
        assertEquals("always", transitions.get(0).getCondition());
    }

    @Test
    @DisplayName("测试获取节点流转规则 - 空列表")
    void testGetTransitionsFromNode_Empty() {
        List<ProcessTransition> transitions = processDefinitionService.getTransitionsFromNode(
                simpleProcess, "nonexistent_node");

        assertNotNull(transitions);
        assertTrue(transitions.isEmpty());
    }

    @Test
    @DisplayName("测试条件流转 - 简单条件")
    void testFindNextTransition_SimpleCondition() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_days", 5);

        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", "true", variables);

        assertNotNull(transition);
        assertEquals("node_director", transition.getToNode());
    }

    @Test
    @DisplayName("测试条件流转 - 条件不满足走默认分支")
    void testFindNextTransition_ConditionNotMet() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_days", 2);

        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", "false", variables);

        assertNotNull(transition);
        assertEquals("node_manager", transition.getToNode());
    }

    @Test
    @DisplayName("测试条件表达式解析 - 大于比较")
    void testConditionExpression_GreaterThan() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_days", 5);

        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", null, variables);

        assertNotNull(transition);
        assertEquals("node_director", transition.getToNode());
    }

    @Test
    @DisplayName("测试条件表达式解析 - 小于等于比较")
    void testConditionExpression_LessThanOrEqual() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_days", 3);

        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", null, variables);

        assertNotNull(transition);
        assertEquals("node_manager", transition.getToNode());
    }

    @Test
    @DisplayName("测试嵌套条件流转 - 病假且天数大于7天")
    void testNestedCondition_SickLeaveMoreThan7Days() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_type", "病假");
        variables.put("leave_days", 10);

        ProcessTransition step1 = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond1", null, variables);

        assertNotNull(step1);
        assertEquals("cond2", step1.getToNode());

        ProcessTransition step2 = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond2", null, variables);

        assertNotNull(step2);
        assertEquals("node_director", step2.getToNode());
    }

    @Test
    @DisplayName("测试嵌套条件流转 - 病假且天数小于等于7天")
    void testNestedCondition_SickLeaveLessThan7Days() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_type", "病假");
        variables.put("leave_days", 5);

        ProcessTransition step1 = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond1", null, variables);

        assertNotNull(step1);
        assertEquals("cond2", step1.getToNode());

        ProcessTransition step2 = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond2", null, variables);

        assertNotNull(step2);
        assertEquals("node_hr", step2.getToNode());
    }

    @Test
    @DisplayName("测试嵌套条件流转 - 非病假走经理审批")
    void testNestedCondition_NonSickLeave() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_type", "事假");
        variables.put("leave_days", 10);

        ProcessTransition transition = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond1", null, variables);

        assertNotNull(transition);
        assertEquals("node_manager", transition.getToNode());
    }

    @Test
    @DisplayName("测试条件表达式解析 - 相等比较")
    void testConditionExpression_Equals() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("leave_type", "病假");

        ProcessTransition transition = processDefinitionService.findNextTransition(
                nestedConditionalProcess, "cond1", null, variables);

        assertNotNull(transition);
        assertEquals("cond2", transition.getToNode());
    }

    @Test
    @DisplayName("测试条件表达式解析 - 变量为空")
    void testConditionExpression_NullVariables() {
        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", null, null);

        assertNull(transition);
    }

    @Test
    @DisplayName("测试条件表达式解析 - 无效表达式")
    void testConditionExpression_InvalidExpression() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("invalid_var", "value");

        ProcessTransition transition = processDefinitionService.findNextTransition(
                conditionalProcess, "node_condition", null, variables);

        assertNull(transition);
    }

    @Test
    @DisplayName("测试始终流转条件")
    void testAlwaysCondition() {
        Map<String, Object> variables = new HashMap<>();

        ProcessTransition transition = processDefinitionService.findNextTransition(
                simpleProcess, "start", null, variables);

        assertNotNull(transition);
        assertEquals("node_approval", transition.getToNode());
        assertEquals("always", transition.getCondition());
    }

    @Test
    @DisplayName("测试流转规则 - 审批通过走结束")
    void testTransition_ApprovedToEnd() {
        ProcessTransition transition = processDefinitionService.findNextTransition(
                simpleProcess, "node_approval", "approved", new HashMap<>());

        assertNotNull(transition);
        assertEquals("end", transition.getToNode());
    }

    @Test
    @DisplayName("测试流转规则 - 没有匹配条件返回null")
    void testTransition_NoMatchingCondition() {
        ProcessTransition transition = processDefinitionService.findNextTransition(
                simpleProcess, "node_approval", "rejected", new HashMap<>());

        assertNull(transition);
    }

    @Test
    @DisplayName("测试更新流程定义")
    void testUpdateProcessDefinition() {
        ProcessDefinition updatedProcess = TestDataBuilder.buildBasicProcessDefinition();
        updatedProcess.setProcessName("更新后的流程名称");

        when(processDefinitionRepository.findByProcessId(simpleProcess.getProcessId()))
                .thenReturn(Optional.of(simpleProcess));
        when(processDefinitionRepository.save(any(ProcessDefinition.class)))
                .thenReturn(updatedProcess);

        ProcessDefinition result = processDefinitionService.updateProcessDefinition(
                simpleProcess.getProcessId(), updatedProcess);

        assertNotNull(result);
        assertEquals("更新后的流程名称", result.getProcessName());
    }

    @Test
    @DisplayName("测试删除流程定义")
    void testDeleteProcessDefinition() {
        when(processDefinitionRepository.findByProcessId(simpleProcess.getProcessId()))
                .thenReturn(Optional.of(simpleProcess));
        doNothing().when(processDefinitionRepository).delete(simpleProcess);

        assertDoesNotThrow(() -> processDefinitionService.deleteProcessDefinition(simpleProcess.getProcessId()));
        verify(processDefinitionRepository, times(1)).delete(simpleProcess);
    }

    @Test
    @DisplayName("测试启用流程定义")
    void testEnableProcessDefinition() {
        simpleProcess.setEnabled(false);
        when(processDefinitionRepository.findByProcessId(simpleProcess.getProcessId()))
                .thenReturn(Optional.of(simpleProcess));
        when(processDefinitionRepository.save(simpleProcess))
                .thenReturn(simpleProcess);

        ProcessDefinition result = processDefinitionService.enableProcessDefinition(simpleProcess.getProcessId());

        assertTrue(result.getEnabled());
    }

    @Test
    @DisplayName("测试禁用流程定义")
    void testDisableProcessDefinition() {
        simpleProcess.setEnabled(true);
        when(processDefinitionRepository.findByProcessId(simpleProcess.getProcessId()))
                .thenReturn(Optional.of(simpleProcess));
        when(processDefinitionRepository.save(simpleProcess))
                .thenReturn(simpleProcess);

        ProcessDefinition result = processDefinitionService.disableProcessDefinition(simpleProcess.getProcessId());

        assertFalse(result.getEnabled());
    }

    @Test
    @DisplayName("测试获取起始节点")
    void testGetStartNode() {
        ProcessNode startNode = processDefinitionService.getStartNode(simpleProcess);

        assertNotNull(startNode);
        assertEquals("start", startNode.getNodeId());
        assertEquals(NodeType.START, startNode.getNodeType());
    }

    @Test
    @DisplayName("测试获取结束节点")
    void testGetEndNode() {
        ProcessNode endNode = processDefinitionService.getEndNode(simpleProcess);

        assertNotNull(endNode);
        assertEquals("end", endNode.getNodeId());
        assertEquals(NodeType.END, endNode.getNodeType());
    }
}
