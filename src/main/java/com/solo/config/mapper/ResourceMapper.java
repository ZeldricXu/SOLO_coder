package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.Resource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {
}
