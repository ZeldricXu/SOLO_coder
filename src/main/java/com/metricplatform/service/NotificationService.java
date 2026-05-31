package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.dto.NotificationSendDTO;
import com.metricplatform.dto.NotificationTemplateDTO;
import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.entity.SysNotificationTemplate;
import com.metricplatform.mapper.SysNotificationRecordMapper;
import com.metricplatform.mapper.SysNotificationTemplateMapper;
import freemarker.template.Configuration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService extends ServiceImpl<SysNotificationTemplateMapper, SysNotificationTemplate> {

    private final ApplicationContext applicationContext;
    private final Configuration freemarkerConfig;
    private final SysNotificationRecordMapper recordMapper;

    private final Map<String, NotificationChannel> channelMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Map<String, NotificationChannel> beans = applicationContext.getBeansOfType(NotificationChannel.class);
        for (NotificationChannel channel : beans.values()) {
            channelMap.put(channel.getChannelName().toUpperCase(), channel);
        }
        log.info("已注册 {} 个通知渠道: {}", channelMap.size(), channelMap.keySet());
    }

    @Transactional(rollbackFor = Exception.class)
    public SysNotificationTemplate createTemplate(NotificationTemplateDTO dto) {
        SysNotificationTemplate template = new SysNotificationTemplate();
        template.setTemplateId("tpl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        template.setTemplateName(dto.getTemplateName());
        template.setChannel(dto.getChannel().toLowerCase());
        template.setSubjectTemplate(dto.getSubjectTemplate());
        template.setContentTemplate(dto.getContentTemplate());
        template.setVariables(dto.getVariables());
        template.setEnabled(dto.getEnabled());

        this.save(template);
        log.info("已创建通知模板: {} (ID: {})", dto.getTemplateName(), template.getTemplateId());
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysNotificationTemplate updateTemplate(String templateId, NotificationTemplateDTO dto) {
        SysNotificationTemplate template = this.getById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        template.setTemplateName(dto.getTemplateName());
        template.setChannel(dto.getChannel().toLowerCase());
        template.setSubjectTemplate(dto.getSubjectTemplate());
        template.setContentTemplate(dto.getContentTemplate());
        template.setVariables(dto.getVariables());
        template.setEnabled(dto.getEnabled());

        this.updateById(template);
        log.info("已更新通知模板: {}", templateId);
        return template;
    }

    public SysNotificationRecord sendNotification(NotificationSendDTO dto) {
        SysNotificationRecord record = buildNotificationRecord(dto);

        if (dto.isAsync()) {
            sendAsync(record);
        } else {
            sendSync(record);
        }

        return record;
    }

    @Async("notificationExecutor")
    public void sendAsync(SysNotificationRecord record) {
        sendSync(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysNotificationRecord sendSync(SysNotificationRecord record) {
        recordMapper.insert(record);

        try {
            NotificationChannel channel = channelMap.get(record.getChannel().toUpperCase());
            if (channel == null) {
                throw new IllegalArgumentException("不支持的通知渠道: " + record.getChannel());
            }

            boolean success = channel.send(record);

            record.setStatus(success ? "sent" : "failed");
            record.setErrorMessage(success ? null : "发送失败");
            record.setSentAt(LocalDateTime.now());

        } catch (Exception e) {
            log.error("发送通知失败: {}", e.getMessage(), e);
            record.setStatus("failed");
            record.setErrorMessage(e.getMessage());
        }

        recordMapper.updateById(record);
        log.info("通知发送完成: {} -> {} (状态: {})", record.getChannel(), record.getReceiver(), record.getStatus());
        return record;
    }

    private SysNotificationRecord buildNotificationRecord(NotificationSendDTO dto) {
        SysNotificationRecord record = new SysNotificationRecord();
        record.setRecordId("notify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        record.setChannel(dto.getChannel().toLowerCase());
        record.setReceiver(dto.getReceiver());
        record.setStatus("pending");

        if (dto.getTemplateId() != null) {
            SysNotificationTemplate template = this.getById(dto.getTemplateId());
            if (template == null) {
                throw new IllegalArgumentException("模板不存在: " + dto.getTemplateId());
            }
            if (!template.getEnabled()) {
                throw new IllegalStateException("模板已禁用: " + dto.getTemplateId());
            }

            record.setTemplateId(dto.getTemplateId());
            record.setSubject(renderTemplate(template.getSubjectTemplate(), dto.getVariables()));
            record.setContent(renderTemplate(template.getContentTemplate(), dto.getVariables()));
        } else {
            record.setSubject(dto.getSubject());
            record.setContent(dto.getContent());
        }

        return record;
    }

    private String renderTemplate(String templateContent, Map<String, Object> variables) {
        if (templateContent == null || templateContent.isEmpty()) {
            return templateContent;
        }
        if (variables == null || variables.isEmpty()) {
            return templateContent;
        }

        try {
            freemarker.template.Template template = new freemarker.template.Template(
                    "dynamic_template", templateContent, freemarkerConfig);
            StringWriter writer = new StringWriter();
            template.process(variables, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("模板渲染失败: {}", e.getMessage(), e);
            return templateContent;
        }
    }

    public SysNotificationRecord getRecord(String recordId) {
        return recordMapper.selectById(recordId);
    }

    public java.util.List<SysNotificationTemplate> getAllTemplates() {
        return this.list();
    }

    public java.util.List<SysNotificationRecord> getRecords(String channel, String status, int limit) {
        LambdaQueryWrapper<SysNotificationRecord> wrapper = new LambdaQueryWrapper<>();
        if (channel != null && !channel.isEmpty()) {
            wrapper.eq(SysNotificationRecord::getChannel, channel.toLowerCase());
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysNotificationRecord::getStatus, status);
        }
        wrapper.orderByDesc(SysNotificationRecord::getCreatedAt);
        wrapper.last("LIMIT " + limit);
        return recordMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTemplate(String templateId) {
        return this.removeById(templateId);
    }

    public java.util.Set<String> getSupportedChannels() {
        return channelMap.keySet();
    }
}
