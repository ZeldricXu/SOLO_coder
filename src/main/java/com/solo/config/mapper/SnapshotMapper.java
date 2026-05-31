package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.Snapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SnapshotMapper extends BaseMapper<Snapshot> {
}
