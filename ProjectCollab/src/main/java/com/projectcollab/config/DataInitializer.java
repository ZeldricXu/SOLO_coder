package com.projectcollab.config;

import com.projectcollab.entity.*;
import com.projectcollab.repository.*;
import com.projectcollab.util.IdGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            ProjectMemberRepository memberRepository,
            StageRepository stageRepository) {
        return args -> {
            if (projectRepository.count() == 0) {
                Project project = new Project();
                project.setProjectId(IdGenerator.generateProjectId());
                project.setProjectName("示例项目");
                project.setProjectType("development");
                project.setProjectStatus("in_progress");
                project.setProjectProgress(50);
                project.setProjectStart(LocalDate.of(2026, 5, 1));
                project.setProjectEnd(LocalDate.of(2026, 5, 30));
                project.setCreatedAt(LocalDateTime.now());
                projectRepository.save(project);

                Stage stage1 = new Stage();
                stage1.setStageId(IdGenerator.generateStageId());
                stage1.setProject(project);
                stage1.setStageName("设计阶段");
                stage1.setStageCode("design");
                stage1.setStageOrder(1);
                stage1.setStageStatus("completed");
                stage1.setStageProgress(100);
                stageRepository.save(stage1);

                Stage stage2 = new Stage();
                stage2.setStageId(IdGenerator.generateStageId());
                stage2.setProject(project);
                stage2.setStageName("开发阶段");
                stage2.setStageCode("development");
                stage2.setStageOrder(2);
                stage2.setStageStatus("in_progress");
                stage2.setStageProgress(50);
                stageRepository.save(stage2);

                Stage stage3 = new Stage();
                stage3.setStageId(IdGenerator.generateStageId());
                stage3.setProject(project);
                stage3.setStageName("测试阶段");
                stage3.setStageCode("testing");
                stage3.setStageOrder(3);
                stage3.setStageStatus("pending");
                stage3.setStageProgress(0);
                stageRepository.save(stage3);

                ProjectMember member1 = new ProjectMember();
                member1.setMemberId(IdGenerator.generateMemberId());
                member1.setProject(project);
                member1.setUserId("user_001");
                member1.setMemberRole("developer");
                member1.setMemberStatus("active");
                member1.setTaskCount(3);
                member1.setCompletedTaskCount(1);
                member1.setCreatedAt(LocalDateTime.now());
                memberRepository.save(member1);

                ProjectMember member2 = new ProjectMember();
                member2.setMemberId(IdGenerator.generateMemberId());
                member2.setProject(project);
                member2.setUserId("user_002");
                member2.setMemberRole("tester");
                member2.setMemberStatus("active");
                member2.setTaskCount(2);
                member2.setCompletedTaskCount(0);
                member2.setCreatedAt(LocalDateTime.now());
                memberRepository.save(member2);
            }
        };
    }
}
