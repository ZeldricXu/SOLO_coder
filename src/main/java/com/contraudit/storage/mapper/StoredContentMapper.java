package com.contraudit.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.storage.entity.StoredContent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoredContentMapper extends BaseMapper<StoredContent> {
}
