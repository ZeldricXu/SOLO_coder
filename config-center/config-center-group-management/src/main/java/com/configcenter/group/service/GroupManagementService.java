package com.configcenter.group.service;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.exception.*;
import com.configcenter.common.util.EntityConverter;
import com.configcenter.group.repository.ConfigGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupManagementService {

    private final ConfigGroupRepository configGroupRepository;

    @Transactional
    public GroupDTO createGroup(CreateGroupRequest request) {
        log.info("Creating group: name={}, environment={}", request.getGroupName(), request.getEnvironment());

        if (configGroupRepository.existsByGroupNameAndEnvironmentAndDeletedFalse(
                request.getGroupName(), request.getEnvironment())) {
            throw new BusinessException("分组已存在: " + request.getGroupName());
        }

        ConfigGroup group = new ConfigGroup();
        group.setGroupName(request.getGroupName());
        group.setEnvironment(request.getEnvironment());
        group.setDescription(request.getDescription());
        group.setCreatedBy(request.getOperator());
        
        if (request.getApplications() != null) {
            group.setApplications(new ArrayList<>(request.getApplications()));
        }
        
        if (request.getParallelPushCount() != null) {
            group.setParallelPushCount(request.getParallelPushCount());
        }
        
        if (request.getGroupType() != null) {
            group.setGroupType(request.getGroupType());
        }

        ConfigGroup saved = configGroupRepository.save(group);
        log.info("Group created: groupId={}, parallelPushCount={}, groupType={}", 
                saved.getGroupId(), saved.getParallelPushCount(), saved.getGroupType());
        return EntityConverter.toGroupDTO(saved);
    }

    public GroupDTO getGroupById(String groupId) {
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        return EntityConverter.toGroupDTO(group);
    }

    public GroupDTO getGroupByName(String groupName, Environment environment) {
        ConfigGroup group = configGroupRepository.findByNameAndEnvironment(groupName, environment)
                .orElseThrow(() -> new GroupNotFoundException(groupName, environment.name()));
        return EntityConverter.toGroupDTO(group);
    }

    public List<GroupDTO> getGroupsByEnvironment(Environment environment) {
        List<ConfigGroup> groups = configGroupRepository.findByEnvironmentAndDeletedFalse(environment);
        List<GroupDTO> result = new ArrayList<>();
        for (ConfigGroup g : groups) {
            result.add(EntityConverter.toGroupDTO(g));
        }
        return result;
    }

    public List<GroupDTO> getAllGroups() {
        List<ConfigGroup> groups = configGroupRepository.findAllActive();
        List<GroupDTO> result = new ArrayList<>();
        for (ConfigGroup g : groups) {
            result.add(EntityConverter.toGroupDTO(g));
        }
        return result;
    }

    public List<GroupDTO> getGroupsByApplication(String application, Environment environment) {
        List<ConfigGroup> groups = configGroupRepository.findByApplicationAndEnvironment(application, environment);
        List<GroupDTO> result = new ArrayList<>();
        for (ConfigGroup g : groups) {
            result.add(EntityConverter.toGroupDTO(g));
        }
        return result;
    }

    @Transactional
    public GroupDTO addApplication(String groupId, String application) {
        log.info("Adding application to group: groupId={}, application={}", groupId, application);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        List<String> applications = group.getApplications();
        if (!applications.contains(application)) {
            applications.add(application);
            group.setApplications(applications);
            ConfigGroup saved = configGroupRepository.save(group);
            log.info("Application added: groupId={}, application={}", groupId, application);
            return EntityConverter.toGroupDTO(saved);
        }
        return EntityConverter.toGroupDTO(group);
    }

    @Transactional
    public GroupDTO removeApplication(String groupId, String application) {
        log.info("Removing application from group: groupId={}, application={}", groupId, application);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        List<String> applications = group.getApplications();
        if (applications.remove(application)) {
            group.setApplications(applications);
            ConfigGroup saved = configGroupRepository.save(group);
            log.info("Application removed: groupId={}, application={}", groupId, application);
            return EntityConverter.toGroupDTO(saved);
        }
        return EntityConverter.toGroupDTO(group);
    }

    @Transactional
    public void deleteGroup(String groupId) {
        log.info("Deleting group: groupId={}", groupId);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        group.setDeleted(true);
        configGroupRepository.save(group);
        log.info("Group deleted: groupId={}", groupId);
    }

    @Transactional
    public GroupDTO updateGroup(String groupId, String description, List<String> applications, 
                                Integer parallelPushCount, String groupType, String operator) {
        log.info("Updating group: groupId={}", groupId);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        if (description != null) {
            group.setDescription(description);
        }
        if (applications != null) {
            group.setApplications(new ArrayList<>(applications));
        }
        if (parallelPushCount != null) {
            group.setParallelPushCount(parallelPushCount);
            log.info("Updated parallelPushCount for group: groupId={}, parallelPushCount={}", groupId, parallelPushCount);
        }
        if (groupType != null) {
            group.setGroupType(groupType);
            log.info("Updated groupType for group: groupId={}, groupType={}", groupId, groupType);
        }
        
        ConfigGroup saved = configGroupRepository.save(group);
        log.info("Group updated: groupId={}", groupId);
        return EntityConverter.toGroupDTO(saved);
    }

    @Transactional
    public GroupDTO setParallelPushCount(String groupId, Integer parallelPushCount, String operator) {
        log.info("Setting parallel push count: groupId={}, count={}", groupId, parallelPushCount);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        group.setParallelPushCount(parallelPushCount);
        ConfigGroup saved = configGroupRepository.save(group);
        log.info("Parallel push count set: groupId={}, count={}", groupId, parallelPushCount);
        return EntityConverter.toGroupDTO(saved);
    }

    @Transactional
    public GroupDTO setGroupType(String groupId, String groupType, String operator) {
        log.info("Setting group type: groupId={}, type={}", groupId, groupType);
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        group.setGroupType(groupType);
        ConfigGroup saved = configGroupRepository.save(group);
        log.info("Group type set: groupId={}, type={}", groupId, groupType);
        return EntityConverter.toGroupDTO(saved);
    }

    public List<String> getApplicationsByGroup(String groupId) {
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        return new ArrayList<>(group.getApplications());
    }

    public Map<String, Object> getGroupStatistics(String groupId) {
        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("groupId", group.getGroupId());
        stats.put("groupName", group.getGroupName());
        stats.put("applicationCount", group.getApplications() != null ? group.getApplications().size() : 0);
        stats.put("parallelPushCount", group.getParallelPushCount());
        stats.put("groupType", group.getGroupType());
        stats.put("environment", group.getEnvironment());
        
        return stats;
    }
}
