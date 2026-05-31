package com.edgescheduler.modules.offline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.offline.domain.OfflineDataCache;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OfflineDataCacheMapper extends BaseMapper<OfflineDataCache> {
}
