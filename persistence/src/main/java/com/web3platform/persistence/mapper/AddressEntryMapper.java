package com.web3platform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.web3platform.persistence.model.entity.AddressEntry;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AddressEntryMapper extends BaseMapper<AddressEntry> {
}
