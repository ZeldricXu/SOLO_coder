package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.BackupRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {
}
