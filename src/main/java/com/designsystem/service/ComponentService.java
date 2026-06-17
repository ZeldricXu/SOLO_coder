package com.designsystem.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.common.PageQuery;
import com.designsystem.entity.Component;
import com.designsystem.entity.ComponentVersion;
import com.designsystem.mapper.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class ComponentService {

    private final ComponentMapper componentMapper;
    private final ComponentVersionMapper versionMapper;
    private final ComponentPropMapper propMapper;
    private final ComponentDocMapper docMapper;
    private final ComponentTokenUsageMapper tokenUsageMapper;
    private final SysUserMapper userMapper;
    private final RabbitTemplate rabbitTemplate;

    public ComponentService(ComponentMapper componentMapper, ComponentVersionMapper versionMapper,
                            ComponentPropMapper propMapper, ComponentDocMapper docMapper,
                            ComponentTokenUsageMapper tokenUsageMapper, SysUserMapper userMapper,
                            RabbitTemplate rabbitTemplate) {
        this.componentMapper = componentMapper;
        this.versionMapper = versionMapper;
        this.propMapper = propMapper;
        this.docMapper = docMapper;
        this.tokenUsageMapper = tokenUsageMapper;
        this.userMapper = userMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    public IPage<Component> getComponentPage(PageQuery query, String category, String framework) {
        Page<Component> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<Component> result = componentMapper.selectComponentPage(page, query.getKeyword(), category, framework);
        result.getRecords().forEach(this::enrichComponent);
        return result;
    }

    public IPage<Component> getMarketplacePage(PageQuery query, String category, String framework, List<String> tags) {
        Page<Component> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<Component> result = componentMapper.selectMarketplacePage(page, query.getKeyword(), category, framework, tags);
        result.getRecords().forEach(this::enrichComponent);
        return result;
    }

    public Component getComponentById(Long id) {
        Component component = componentMapper.selectById(id);
        if (component != null) {
            enrichComponent(component);
            component.setVersions(versionMapper.selectByComponentId(id));
        }
        return component;
    }

    public Component getComponentByNameAndFramework(String name, String framework) {
        return componentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Component>()
                        .eq(Component::getName, name)
                        .eq(Component::getFramework, framework)
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public Component createComponent(Component component) {
        if (component.getTagList() != null && !component.getTagList().isEmpty()) {
            component.setTags(String.join(",", component.getTagList()));
        }
        component.setPublished(0);
        component.setStatus(1);
        componentMapper.insert(component);
        return component;
    }

    @Transactional(rollbackFor = Exception.class)
    public Component updateComponent(Component component) {
        if (component.getTagList() != null && !component.getTagList().isEmpty()) {
            component.setTags(String.join(",", component.getTagList()));
        }
        componentMapper.updateById(component);
        return component;
    }

    @Transactional(rollbackFor = Exception.class)
    public void publishComponent(Long componentId, String version) {
        Component component = componentMapper.selectById(componentId);
        if (component == null) {
            throw new RuntimeException("Component not found");
        }

        component.setLatestVersion(version);
        component.setPublished(1);
        componentMapper.updateById(component);

        ComponentVersion latestVersion = versionMapper.selectLatestVersion(componentId);
        if (latestVersion != null) {
            latestVersion.setIsLatest(0);
            versionMapper.updateById(latestVersion);
        }

        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_COMPONENT_PUBLISH, componentId);
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_CHANGELOG_GENERATE, componentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ComponentVersion createVersion(ComponentVersion version) {
        version.setIsLatest(1);
        version.setIsPrerelease(version.getIsPrerelease() != null ? version.getIsPrerelease() : 0);
        versionMapper.insert(version);
        return version;
    }

    public ComponentVersion getVersionById(Long versionId) {
        ComponentVersion version = versionMapper.selectById(versionId);
        if (version != null) {
            version.setProps(propMapper.selectByVersionId(versionId));
            version.setDocs(docMapper.selectByVersionId(versionId));
        }
        return version;
    }

    public List<ComponentVersion> getVersionsByComponentId(Long componentId) {
        return versionMapper.selectByComponentId(componentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackVersion(Long componentId, String targetVersion) {
        ComponentVersion version = versionMapper.selectByComponentIdAndVersion(componentId, targetVersion);
        if (version == null) {
            throw new RuntimeException("Version not found");
        }

        Component component = componentMapper.selectById(componentId);
        component.setLatestVersion(targetVersion);
        componentMapper.updateById(component);

        List<ComponentVersion> allVersions = versionMapper.selectByComponentId(componentId);
        for (ComponentVersion v : allVersions) {
            v.setIsLatest(v.getVersion().equals(targetVersion) ? 1 : 0);
            versionMapper.updateById(v);
        }
    }

    public List<Component> getComponentsByTokenId(Long tokenId) {
        return componentMapper.selectComponentsByTokenId(tokenId);
    }

    private void enrichComponent(Component component) {
        if (component.getMaintainerId() != null) {
            component.setMaintainer(userMapper.selectById(component.getMaintainerId()));
        }
        if (component.getTags() != null && !component.getTags().isEmpty()) {
            component.setTagList(Arrays.asList(component.getTags().split(",")));
        }
    }
}
