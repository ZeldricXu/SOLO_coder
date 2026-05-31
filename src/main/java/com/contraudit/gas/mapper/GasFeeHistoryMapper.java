package com.contraudit.gas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.contraudit.gas.entity.GasFeeHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface GasFeeHistoryMapper extends BaseMapper<GasFeeHistory> {

    @Select("SELECT chain_type, tx_type, " +
            "ROUND(AVG(gas_price), 9) as avg_price, " +
            "ROUND(AVG(gas_used), 9) as avg_used, " +
            "ROUND(AVG(priority_fee), 9) as avg_priority_fee " +
            "FROM gas_fee_history " +
            "WHERE chain_type = #{chainType} " +
            "AND created_at >= #{fromTime} " +
            "GROUP BY chain_type, tx_type")
    List<Map<String, Object>> getAverageGasStats(@Param("chainType") String chainType,
                                                 @Param("fromTime") LocalDateTime fromTime);
}
