package com.projectcollab.service.member;

import com.projectcollab.dto.AddMemberRequest;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.ProjectMemberRepository;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class MemberService {

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private ProjectService projectService;

    public List<ProjectMember> getMembersByProjectId(String projectId) {
        return memberRepository.findByProject_ProjectId(projectId);
    }

    public Optional<ProjectMember> findMemberByProjectAndUser(String projectId, String userId) {
        return memberRepository.findByProject_ProjectIdAndUserId(projectId, userId);
    }

    @Transactional
    public ProjectMember addMember(AddMemberRequest request) {
        Project project = projectService.getProjectOrThrow(request.getProjectId());
        
        Optional<ProjectMember> existing = memberRepository.findByProject_ProjectIdAndUserId(
                request.getProjectId(), request.getUserId());
        if (existing.isPresent()) {
            throw new ProjectCollabException(400, "成员已存在于项目中");
        }

        ProjectMember member = new ProjectMember();
        member.setMemberId(IdGenerator.generateMemberId());
        member.setProject(project);
        member.setUserId(request.getUserId());
        member.setMemberRole(request.getMemberRole() != null ? request.getMemberRole() : "developer");
        member.setMemberStatus("active");
        member.setTaskCount(0);
        member.setCompletedTaskCount(0);
        member.setCreatedAt(LocalDateTime.now());
        
        return memberRepository.save(member);
    }

    public List<ProjectMember> getAvailableMembers(String projectId) {
        List<ProjectMember> members = memberRepository.findByProject_ProjectIdAndMemberStatus(
                projectId, "active");
        
        if (members.isEmpty()) {
            throw new ProjectCollabException(400, "项目成员不足，无法分配任务");
        }
        
        return members;
    }

    public ProjectMember selectOptimalMember(String projectId) {
        List<ProjectMember> members = getAvailableMembers(projectId);
        
        return members.stream()
                .filter(m -> "active".equals(m.getMemberStatus()))
                .filter(m -> m.getTaskCount() < 10)
                .min(Comparator.comparingInt(ProjectMember::getTaskCount))
                .orElseThrow(() -> new ProjectCollabException(400, "没有可用的成员来分配任务"));
    }

    @Transactional
    public void incrementTaskCount(String memberId) {
        ProjectMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ProjectCollabException(404, "成员不存在: " + memberId));
        member.setTaskCount(member.getTaskCount() + 1);
        memberRepository.save(member);
    }

    @Transactional
    public void decrementTaskCountAndIncrementCompleted(String userId, String projectId) {
        Optional<ProjectMember> optMember = memberRepository.findByProject_ProjectIdAndUserId(
                projectId, userId);
        
        if (optMember.isPresent()) {
            ProjectMember member = optMember.get();
            if (member.getTaskCount() > 0) {
                member.setTaskCount(member.getTaskCount() - 1);
            }
            member.setCompletedTaskCount(member.getCompletedTaskCount() + 1);
            memberRepository.save(member);
        }
    }
}
