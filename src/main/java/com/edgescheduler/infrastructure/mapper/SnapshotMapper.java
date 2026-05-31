package com.edgescheduler.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.domain.entity.SnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SnapshotMapper extends BaseMapper<SnapshotEntity> {
}
