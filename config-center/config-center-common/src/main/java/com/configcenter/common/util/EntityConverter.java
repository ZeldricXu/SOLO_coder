package com.configcenter.common.util;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;

public class EntityConverter {

    private EntityConverter() {
    }

    public static ConfigDTO toConfigDTO(ConfigItem item) {
        if (item == null) {
            return null;
        }
        ConfigDTO dto = new ConfigDTO();
        dto.setConfigId(item.getConfigId());
        dto.setConfigKey(item.getConfigKey());
        dto.setConfigValue(item.getConfigValue());
        dto.setConfigType(item.getConfigType());
        dto.setIsEncrypted(item.getIsEncrypted());
        dto.setEnvironment(item.getEnvironment());
        dto.setGroupId(item.getGroupId());
        dto.setDescription(item.getDescription());
        dto.setCurrentVersion(item.getCurrentVersion());
        dto.setCreatedAt(item.getCreatedAt());
        dto.setUpdatedAt(item.getUpdatedAt());
        dto.setCreatedBy(item.getCreatedBy());
        dto.setUpdatedBy(item.getUpdatedBy());
        return dto;
    }

    public static VersionDTO toVersionDTO(ConfigVersion version) {
        if (version == null) {
            return null;
        }
        VersionDTO dto = new VersionDTO();
        dto.setVersionId(version.getVersionId());
        dto.setConfigId(version.getConfigId());
        dto.setVersion(version.getVersion());
        dto.setConfigValue(version.getConfigValue());
        dto.setChangeReason(version.getChangeReason());
        dto.setChangedBy(version.getChangedBy());
        dto.setChangedAt(version.getChangedAt());
        dto.setIsRollback(version.getIsRollback());
        dto.setRollbackFromVersion(version.getRollbackFromVersion());
        return dto;
    }

    public static GroupDTO toGroupDTO(ConfigGroup group) {
        if (group == null) {
            return null;
        }
        GroupDTO dto = new GroupDTO();
        dto.setGroupId(group.getGroupId());
        dto.setGroupName(group.getGroupName());
        dto.setEnvironment(group.getEnvironment());
        dto.setDescription(group.getDescription());
        dto.setApplications(group.getApplications());
        dto.setCreatedAt(group.getCreatedAt());
        dto.setUpdatedAt(group.getUpdatedAt());
        dto.setCreatedBy(group.getCreatedBy());
        return dto;
    }

    public static PushResultDTO toPushResultDTO(PushRecord record) {
        if (record == null) {
            return null;
        }
        PushResultDTO dto = new PushResultDTO();
        dto.setPushId(record.getPushId());
        dto.setConfigId(record.getConfigId());
        dto.setVersion(record.getVersion());
        dto.setTargetGroup(record.getTargetGroup());
        dto.setPushStatus(record.getPushStatus());
        dto.setPushTime(record.getPushTime());
        dto.setSuccessCount(record.getSuccessCount());
        dto.setFailCount(record.getFailCount());
        dto.setTotalCount(record.getTotalCount());
        return dto;
    }

    public static AuditRecordDTO toAuditRecordDTO(AuditRecord record) {
        if (record == null) {
            return null;
        }
        AuditRecordDTO dto = new AuditRecordDTO();
        dto.setAuditId(record.getAuditId());
        dto.setConfigId(record.getConfigId());
        dto.setOperation(record.getOperation());
        dto.setOldValue(record.getOldValue());
        dto.setNewValue(record.getNewValue());
        dto.setOperator(record.getOperator());
        dto.setOperatedAt(record.getOperatedAt());
        dto.setRemark(record.getRemark());
        dto.setIpAddress(record.getIpAddress());
        return dto;
    }
}
