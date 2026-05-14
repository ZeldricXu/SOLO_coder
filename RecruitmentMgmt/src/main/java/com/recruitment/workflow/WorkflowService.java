package com.recruitment.workflow;

import com.recruitment.common.enums.InterviewType;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.model.Workflow;
import com.recruitment.repository.WorkflowRepository;
import com.recruitment.service.PositionTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {
    private final WorkflowRepository workflowRepository;
    private final PositionTypeConfigService positionTypeConfigService;

    @PostConstruct
    @Transactional
    public void initDefaultWorkflows() {
        if (workflowRepository.count() == 0) {
            createDefaultWorkflow("TECHNICAL", "技术岗默认流程",
                    Arrays.asList(InterviewType.TECHNICAL, InterviewType.MANAGERIAL, InterviewType.HR));
            createDefaultWorkflow("PRODUCT", "产品岗默认流程",
                    Arrays.asList(InterviewType.BEHAVIORAL, InterviewType.MANAGERIAL, InterviewType.HR));
            createDefaultWorkflow("DEFAULT", "通用默认流程",
                    Arrays.asList(InterviewType.TECHNICAL, InterviewType.HR));
            log.info("Workflow: 已初始化默认招聘流程");
        }
    }

    private void createDefaultWorkflow(String positionType, String name, List<InterviewType> stages) {
        Workflow workflow = Workflow.builder()
                .workflowId(IdGenerator.generateWorkflowId())
                .workflowName(name)
                .positionType(positionType)
                .isDefault(true)
                .stages(stages)
                .screenRules("基本筛选规则：学历要求、工作经验要求")
                .build();
        workflowRepository.save(workflow);
    }

    public Workflow getWorkflowByPositionType(String positionType) {
        Optional<Workflow> workflow = workflowRepository.findByPositionTypeAndIsDefaultTrue(positionType);
        if (workflow.isPresent()) {
            return workflow.get();
        }
        return workflowRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new RuntimeException("未找到默认招聘流程"));
    }

    public InterviewType getNextInterviewStage(String positionType, int currentStageIndex) {
        List<InterviewType> stages = getInterviewStages(positionType);
        if (currentStageIndex < stages.size()) {
            return stages.get(currentStageIndex);
        }
        return null;
    }

    public List<InterviewType> getInterviewStages(String positionType) {
        List<InterviewType> configStages = positionTypeConfigService.getInterviewStagesForType(positionType);
        if (configStages != null && !configStages.isEmpty()) {
            return configStages;
        }
        try {
            Workflow workflow = getWorkflowByPositionType(positionType);
            return workflow.getStages();
        } catch (RuntimeException e) {
            return Arrays.asList(InterviewType.TECHNICAL, InterviewType.HR);
        }
    }

    public int getTotalInterviewStages(String positionType) {
        return getInterviewStages(positionType).size();
    }

    public boolean isLastStage(String positionType, int currentStageIndex) {
        List<InterviewType> stages = getInterviewStages(positionType);
        return currentStageIndex >= stages.size() - 1;
    }

    @Transactional
    public Workflow createWorkflow(String name, String positionType, List<InterviewType> stages,
                                   String screenRules, boolean isDefault) {
        Workflow workflow = Workflow.builder()
                .workflowId(IdGenerator.generateWorkflowId())
                .workflowName(name)
                .positionType(positionType)
                .isDefault(isDefault)
                .stages(stages)
                .screenRules(screenRules)
                .build();
        return workflowRepository.save(workflow);
    }

    public List<Workflow> getAllWorkflows() {
        return workflowRepository.findAll();
    }

    public Workflow getWorkflowById(String workflowId) {
        return workflowRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new RuntimeException("流程不存在: " + workflowId));
    }

    public boolean evaluateScreenRules(String candidateEducation, String candidateExperience,
                                       String positionRequirement) {
        if (candidateEducation == null || candidateExperience == null) {
            return true;
        }
        return true;
    }
}
