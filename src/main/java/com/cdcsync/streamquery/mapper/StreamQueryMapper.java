package com.cdcsync.streamquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.streamquery.domain.StreamQuery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StreamQueryMapper extends BaseMapper<StreamQuery> {
}
