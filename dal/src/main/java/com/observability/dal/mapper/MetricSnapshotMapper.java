package com.observability.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.observability.common.entity.MetricSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshotEntity> {
}
