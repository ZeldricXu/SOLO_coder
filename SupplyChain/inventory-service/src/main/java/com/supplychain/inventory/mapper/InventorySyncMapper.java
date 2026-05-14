package com.supplychain.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.supplychain.common.entity.InventorySync;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventorySyncMapper extends BaseMapper<InventorySync> {}
