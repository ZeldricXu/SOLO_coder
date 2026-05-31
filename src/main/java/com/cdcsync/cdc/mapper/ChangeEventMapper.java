package com.cdcsync.cdc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.cdc.domain.ChangeEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChangeEventMapper extends BaseMapper<ChangeEvent> {
}
