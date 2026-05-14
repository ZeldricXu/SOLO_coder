package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.Agent;
import com.servicedesk.repository.AgentRepository;
import com.servicedesk.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("负载预警服务测试")
class LoadWarningServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private LoadWarningService loadWarningService;

    private ServiceDeskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ServiceDeskProperties();
        properties.getLoadWarning().setDefaultWarningThreshold(0.7);
        properties.getLoadWarning().setSmallTeamSize(3);
        properties.getLoadWarning().setMediumTeamSize(6);
        properties.getLoadWarning().setSmallTeamThreshold(0.8);
        properties.getLoadWarning().setMediumTeamThreshold(0.7);
        properties.getLoadWarning().setLargeTeamThreshold(0.6);

        loadWarningService = new LoadWarningService(agentRepository, properties, eventPublisher);
        loadWarningService.clearAllWarnings();
    }

    @Test
    @DisplayName("测试负载预警阈值计算 - 基于团队规模")
    void testGetWarningThresholdByTeamSize() {
        List<Agent> smallTeam = new ArrayList<>();
        smallTeam.add(TestDataBuilder.createAgent("agent_001", "technical_support"));
        smallTeam.add(TestDataBuilder.createAgent("agent_002", "technical_support"));

        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(smallTeam);

        Agent agent = TestDataBuilder.createAgent("agent_001", "technical_support");
        double threshold = loadWarningService.getWarningThreshold("technical_support", 2);

        assertEquals(0.8, threshold, "小团队阈值应为0.8");
    }

    @Test
    @DisplayName("测试负载预警触发 - 高负载客服触发预警")
    void testHighLoadAgentTriggersWarning() {
        Agent highLoadAgent = TestDataBuilder.createHighLoadAgent("agent_001", "technical_support");
        List<Agent> agents = Collections.singletonList(highLoadAgent);

        when(agentRepository.findAll()).thenReturn(agents);
        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(agents);

        List<Agent> warnedAgents = loadWarningService.checkAndWarnHighLoadAgents();

        assertEquals(1, warnedAgents.size(), "应该有1个客服触发预警");
        assertTrue(loadWarningService.isAgentWarned("agent_001"), "客服应被标记为已警告");
        verify(eventPublisher, times(1)).publishEvent(any(LoadWarningService.LoadWarningEvent.class));
    }

    @Test
    @DisplayName("测试预警触发后客服调度增加流程")
    void testAgentLoadReductionClearsWarning() {
        Agent highLoadAgent = TestDataBuilder.createHighLoadAgent("agent_001", "technical_support");
        List<Agent> highLoadList = Collections.singletonList(highLoadAgent);
        when(agentRepository.findAll()).thenReturn(highLoadList);
        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(highLoadList);

        List<Agent> firstCheck = loadWarningService.checkAndWarnHighLoadAgents();
        assertEquals(1, firstCheck.size());
        assertTrue(loadWarningService.isAgentWarned("agent_001"));

        Agent normalLoadAgent = TestDataBuilder.createAgentWithLoad("agent_001", "technical_support", 2, 5);
        List<Agent> normalLoadList = Collections.singletonList(normalLoadAgent);
        when(agentRepository.findAll()).thenReturn(normalLoadList);
        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(normalLoadList);

        List<Agent> secondCheck = loadWarningService.checkAndWarnHighLoadAgents();
        assertEquals(0, secondCheck.size(), "负载降低后不应再触发预警");
        assertFalse(loadWarningService.isAgentWarned("agent_001"), "警告应被清除");
    }

    @Test
    @DisplayName("测试不同团队规模下的预警阈值差异 - 小团队")
    void testDifferentThresholdsForSmallTeam() {
        double smallTeamThreshold = properties.getLoadWarning().getThresholdByTeamSize(2);
        assertEquals(0.8, smallTeamThreshold, "小团队(2人)阈值应为0.8");
    }

    @Test
    @DisplayName("测试不同团队规模下的预警阈值差异 - 中等团队")
    void testDifferentThresholdsForMediumTeam() {
        double mediumTeamThreshold = properties.getLoadWarning().getThresholdByTeamSize(5);
        assertEquals(0.7, mediumTeamThreshold, "中等团队(5人)阈值应为0.7");
    }

    @Test
    @DisplayName("测试不同团队规模下的预警阈值差异 - 大团队")
    void testDifferentThresholdsForLargeTeam() {
        double largeTeamThreshold = properties.getLoadWarning().getThresholdByTeamSize(10);
        assertEquals(0.6, largeTeamThreshold, "大团队(10人)阈值应为0.6");
    }

    @Test
    @DisplayName("测试预警阈值配置加载正确性")
    void testConfigurationLoading() {
        assertEquals(0.7, properties.getLoadWarning().getDefaultWarningThreshold());
        assertEquals(3, properties.getLoadWarning().getSmallTeamSize());
        assertEquals(6, properties.getLoadWarning().getMediumTeamSize());
        assertEquals(0.8, properties.getLoadWarning().getSmallTeamThreshold());
        assertEquals(0.7, properties.getLoadWarning().getMediumTeamThreshold());
        assertEquals(0.6, properties.getLoadWarning().getLargeTeamThreshold());
    }

    @Test
    @DisplayName("测试临界负载不触发预警")
    void testBoundaryLoadDoesNotTriggerWarning() {
        Agent boundaryAgent = TestDataBuilder.createAgentWithLoad("agent_001", "technical_support", 3, 5);
        List<Agent> agents = Collections.singletonList(boundaryAgent);

        when(agentRepository.findAll()).thenReturn(agents);
        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(agents);

        List<Agent> warnedAgents = loadWarningService.checkAndWarnHighLoadAgents();

        assertEquals(0, warnedAgents.size(), "3/5=60%负载不应触发预警");
        verify(eventPublisher, never()).publishEvent(any(LoadWarningService.LoadWarningEvent.class));
    }

    @Test
    @DisplayName("测试相同客服重复检查只触发一次预警")
    void testSameAgentWarnedOnlyOnce() {
        Agent highLoadAgent = TestDataBuilder.createHighLoadAgent("agent_001", "technical_support");
        List<Agent> agents = Collections.singletonList(highLoadAgent);

        when(agentRepository.findAll()).thenReturn(agents);
        when(agentRepository.findByAgentGroup("technical_support")).thenReturn(agents);

        loadWarningService.checkAndWarnHighLoadAgents();
        loadWarningService.checkAndWarnHighLoadAgents();

        verify(eventPublisher, times(1)).publishEvent(any(LoadWarningService.LoadWarningEvent.class));
    }

    @Test
    @DisplayName("测试多个高负载客服同时触发预警")
    void testMultipleHighLoadAgents() {
        Agent agent1 = TestDataBuilder.createHighLoadAgent("agent_001", "technical_support");
        Agent agent2 = TestDataBuilder.createHighLoadAgent("agent_002", "technical_support");
        Agent agent3 = TestDataBuilder.createAgent("agent_003", "technical_support");

        List<Agent> allAgents = new ArrayList<>();
        allAgents.add(agent1);
        allAgents.add(agent2);
        allAgents.add(agent3);

        when(agentRepository.findAll()).thenReturn(allAgents);
        when(agentRepository.findByAgentGroup(anyString())).thenReturn(allAgents);

        List<Agent> warnedAgents = loadWarningService.checkAndWarnHighLoadAgents();

        assertEquals(2, warnedAgents.size(), "应检测到2个高负载客服");
        verify(eventPublisher, times(2)).publishEvent(any(LoadWarningService.LoadWarningEvent.class));
    }
}
