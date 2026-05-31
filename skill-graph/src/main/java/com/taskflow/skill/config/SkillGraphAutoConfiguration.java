package com.taskflow.skill.config;

import com.taskflow.skill.api.LearningPathService;
import com.taskflow.skill.api.SkillAssessmentService;
import com.taskflow.skill.api.SkillTreeService;
import com.taskflow.skill.internal.assessment.SkillAssessmentServiceImpl;
import com.taskflow.skill.internal.learning.LearningPathServiceImpl;
import com.taskflow.skill.internal.tree.SkillTreeServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 技能图谱模块自动配置
 */
@Configuration
public class SkillGraphAutoConfiguration {

    @Bean
    public SkillTreeService skillTreeService() {
        return new SkillTreeServiceImpl();
    }

    @Bean
    public SkillAssessmentService skillAssessmentService() {
        return new SkillAssessmentServiceImpl();
    }

    @Bean
    public LearningPathService learningPathService() {
        return new LearningPathServiceImpl();
    }
}
