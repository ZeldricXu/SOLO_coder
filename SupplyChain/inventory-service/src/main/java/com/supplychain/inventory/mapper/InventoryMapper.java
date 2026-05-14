package com.supplychain.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.supplychain.common.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {}
