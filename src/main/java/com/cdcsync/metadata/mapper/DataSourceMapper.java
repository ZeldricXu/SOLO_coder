package com.cdcsync.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.metadata.domain.DataSource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {
}
