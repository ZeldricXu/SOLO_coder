package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.BillingItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillingItemMapper extends BaseMapper<BillingItem> {
}
