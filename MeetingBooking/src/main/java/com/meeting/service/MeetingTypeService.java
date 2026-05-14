package com.meeting.service;

import com.meeting.entity.MeetingType;
import com.meeting.exception.MeetingException;
import com.meeting.repository.MeetingTypeRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingTypeService {

    private final MeetingTypeRepository typeRepository;

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";

    public List<MeetingType> getAllTypes() {
        return typeRepository.findAll();
    }

    public List<MeetingType> getActiveTypes() {
        return typeRepository.findByStatusOrderByTypeNameAsc(STATUS_ACTIVE);
    }

    public MeetingType getTypeById(String typeId) {
        return typeRepository.findByTypeId(typeId)
                .orElseThrow(() -> new MeetingException(404, "会议类型不存在: " + typeId));
    }

    public MeetingType getTypeByCode(String typeCode) {
        return typeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new MeetingException(404, "会议类型不存在: " + typeCode));
    }

    @Transactional
    public MeetingType createType(MeetingType type) {
        if (type.getTypeId() == null || type.getTypeId().isEmpty()) {
            type.setTypeId(IdGenerator.generateTypeId());
        }
        if (type.getStatus() == null || type.getStatus().isEmpty()) {
            type.setStatus(STATUS_ACTIVE);
        }
        if (type.getDefaultDurationMinutes() == null) {
            type.setDefaultDurationMinutes(60);
        }
        if (type.getRequiredApproval() == null) {
            type.setRequiredApproval(false);
        }

        if (typeRepository.existsByTypeCode(type.getTypeCode())) {
            throw new MeetingException(409, "会议类型编码已存在: " + type.getTypeCode());
        }

        log.info("创建会议类型: typeCode={}, typeName={}", type.getTypeCode(), type.getTypeName());
        return typeRepository.save(type);
    }

    @Transactional
    public MeetingType updateType(String typeId, MeetingType typeUpdate) {
        MeetingType existingType = getTypeById(typeId);

        if (typeUpdate.getTypeCode() != null) {
            if (!typeUpdate.getTypeCode().equals(existingType.getTypeCode()) &&
                    typeRepository.existsByTypeCode(typeUpdate.getTypeCode())) {
                throw new MeetingException(409, "会议类型编码已存在: " + typeUpdate.getTypeCode());
            }
            existingType.setTypeCode(typeUpdate.getTypeCode());
        }
        if (typeUpdate.getTypeName() != null) {
            existingType.setTypeName(typeUpdate.getTypeName());
        }
        if (typeUpdate.getDescription() != null) {
            existingType.setDescription(typeUpdate.getDescription());
        }
        if (typeUpdate.getDefaultDurationMinutes() != null) {
            existingType.setDefaultDurationMinutes(typeUpdate.getDefaultDurationMinutes());
        }
        if (typeUpdate.getRequiredApproval() != null) {
            existingType.setRequiredApproval(typeUpdate.getRequiredApproval());
        }
        if (typeUpdate.getStatus() != null) {
            existingType.setStatus(typeUpdate.getStatus());
        }

        log.info("更新会议类型: typeId={}", typeId);
        return typeRepository.save(existingType);
    }

    @Transactional
    public void deleteType(String typeId) {
        MeetingType type = getTypeById(typeId);
        log.info("删除会议类型: typeId={}", typeId);
        typeRepository.delete(type);
    }

    @Transactional
    public MeetingType activateType(String typeId) {
        MeetingType type = getTypeById(typeId);
        type.setStatus(STATUS_ACTIVE);
        log.info("激活会议类型: typeId={}", typeId);
        return typeRepository.save(type);
    }

    @Transactional
    public MeetingType deactivateType(String typeId) {
        MeetingType type = getTypeById(typeId);
        type.setStatus(STATUS_INACTIVE);
        log.info("停用会议类型: typeId={}", typeId);
        return typeRepository.save(type);
    }

    public boolean isTypeActive(String typeCode) {
        return typeRepository.findByTypeCode(typeCode)
                .map(t -> STATUS_ACTIVE.equals(t.getStatus()))
                .orElse(false);
    }

    public boolean exists(String typeCode) {
        return typeRepository.existsByTypeCode(typeCode);
    }
}
