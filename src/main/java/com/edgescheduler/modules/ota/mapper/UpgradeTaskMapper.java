package com.edgescheduler.modules.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.ota.domain.UpgradeTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UpgradeTaskMapper extends BaseMapper<UpgradeTask> {
}
