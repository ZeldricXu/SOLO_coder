package com.cdcsync.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.metadata.domain.SchemaInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SchemaInfoMapper extends BaseMapper<SchemaInfo> {
}
