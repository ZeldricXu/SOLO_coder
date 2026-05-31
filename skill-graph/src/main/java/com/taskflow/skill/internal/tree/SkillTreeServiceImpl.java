package com.taskflow.skill.internal.tree;

import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.data.entity.SkillEntity;
import com.taskflow.data.mapper.SkillMapper;
import com.taskflow.skill.api.SkillTreeService;
import com.taskflow.skill.domain.Skill;
import com.taskflow.skill.domain.SkillNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能树服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillTreeServiceImpl implements SkillTreeService {

    private final SkillMapper skillMapper;

    @Override
    public Mono<SkillNode> getSkillTree(String tenantId) {
        return Mono.fromCallable(() -> {
            List<SkillEntity> allSkills = skillMapper.selectRootSkills(tenantId);
            Map<String, List<SkillEntity>> childrenMap = new HashMap<>();

            for (SkillEntity skill : allSkills) {
                buildChildrenMap(tenantId, skill, childrenMap);
            }

            SkillNode root = new SkillNode();
            root.setSkillId("root");
            root.setName("技能树");
            root.setChildren(buildTreeNodes(tenantId, null, childrenMap));

            return root;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void buildChildrenMap(String tenantId, SkillEntity parent, Map<String, List<SkillEntity>> childrenMap) {
        List<SkillEntity> children = skillMapper.selectByParentId(tenantId, parent.getSkillId());
        if (!children.isEmpty()) {
            childrenMap.put(parent.getSkillId(), children);
            for (SkillEntity child : children) {
                buildChildrenMap(tenantId, child, childrenMap);
            }
        }
    }

    private List<SkillNode> buildTreeNodes(String tenantId, String parentId, Map<String, List<SkillEntity>> childrenMap) {
        List<SkillEntity> skills;
        if (parentId == null) {
            skills = skillMapper.selectRootSkills(tenantId);
        } else {
            skills = childrenMap.getOrDefault(parentId, Collections.emptyList());
        }

        return skills.stream()
                .map(this::convertToNode)
                .peek(node -> node.setChildren(buildTreeNodes(tenantId, node.getSkillId(), childrenMap)))
                .collect(Collectors.toList());
    }

    @Override
    public Mono<SkillNode> getSkill(String tenantId, String skillId) {
        return Mono.fromCallable(() -> {
            SkillEntity entity = skillMapper.selectOne(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.<SkillEntity>lambdaQuery()
                            .eq(SkillEntity::getTenantId, tenantId)
                            .eq(SkillEntity::getSkillId, skillId)
            );

            if (entity == null) {
                throw new ResourceNotFoundException("Skill", skillId);
            }

            SkillNode node = convertToNode(entity);
            node.setChildren(buildTreeNodes(tenantId, skillId, new HashMap<>()));
            return node;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Skill> createSkill(String tenantId, Skill skill) {
        return Mono.fromCallable(() -> {
            SkillEntity entity = new SkillEntity();
            entity.setTenantId(tenantId);
            entity.setSkillId(skill.getSkillId() != null ? skill.getSkillId() : "skill_" + System.currentTimeMillis());
            entity.setName(skill.getName());
            entity.setCode(skill.getCode());
            entity.setDescription(skill.getDescription());
            entity.setCategory(skill.getCategory());
            entity.setLevel(skill.getLevel() != null ? skill.getLevel() : 1);
            entity.setParentId(skill.getParentId());
            entity.setSortOrder(skill.getSortOrder() != null ? skill.getSortOrder() : 0);
            if (skill.getPrerequisiteSkills() != null) {
                entity.setPrerequisites(JsonUtils.toJson(skill.getPrerequisiteSkills()));
            }

            skillMapper.insert(entity);
            log.info("Skill created: {} - {}", entity.getSkillId(), entity.getName());
            return toDomain(entity);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteSkill(String tenantId, String skillId) {
        return Mono.fromRunnable(() -> {
            SkillEntity skill = skillMapper.selectOne(
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.<SkillEntity>lambdaQuery()
                            .eq(SkillEntity::getTenantId, tenantId)
                            .eq(SkillEntity::getSkillId, skillId)
            );
            if (skill != null) {
                skillMapper.deleteById(skill.getId());
                log.info("Skill deleted: {}", skillId);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<List<Skill>> getSkillsByCategory(String tenantId, String category) {
        return Mono.fromCallable(() -> {
            List<SkillEntity> entities = skillMapper.selectByCategory(tenantId, category);
            return entities.stream()
                    .map(this::toDomain)
                    .collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private SkillNode convertToNode(SkillEntity entity) {
        SkillNode node = new SkillNode();
        node.setSkillId(entity.getSkillId());
        node.setTenantId(entity.getTenantId());
        node.setName(entity.getName());
        node.setCode(entity.getCode());
        node.setDescription(entity.getDescription());
        node.setCategory(entity.getCategory());
        node.setLevel(entity.getLevel());
        node.setParentId(entity.getParentId());
        node.setSortOrder(entity.getSortOrder());

        if (entity.getPrerequisites() != null) {
            List<String> prerequisites = JsonUtils.fromJson(entity.getPrerequisites(), List.class);
            node.setPrerequisiteSkills(prerequisites);
        }

        return node;
    }

    private Skill toDomain(SkillEntity entity) {
        Skill.SkillBuilder builder = Skill.builder()
                .skillId(entity.getSkillId())
                .tenantId(entity.getTenantId())
                .name(entity.getName())
                .code(entity.getCode())
                .description(entity.getDescription())
                .category(entity.getCategory())
                .level(entity.getLevel())
                .parentId(entity.getParentId())
                .sortOrder(entity.getSortOrder());

        if (entity.getPrerequisites() != null) {
            List<String> prerequisites = JsonUtils.fromJson(entity.getPrerequisites(), List.class);
            builder.prerequisiteSkills(prerequisites);
        }

        return builder.build();
    }
}
