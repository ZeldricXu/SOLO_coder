package com.edgescheduler.modules.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.ota.domain.FirmwarePackage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FirmwarePackageMapper extends BaseMapper<FirmwarePackage> {
}
