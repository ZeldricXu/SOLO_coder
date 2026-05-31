package com.contraudit.multisig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.multisig.entity.MultisigApproval;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MultisigApprovalMapper extends BaseMapper<MultisigApproval> {
}
