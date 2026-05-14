package com.invoice.mgmt.statistics.mapper;

import com.invoice.mgmt.common.entity.InvoiceStatistics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceStatisticsMapper {
    int insert(InvoiceStatistics stat);

    int update(InvoiceStatistics stat);

    InvoiceStatistics findById(@Param("statId") String statId);

    InvoiceStatistics findByMonth(@Param("statMonth") String statMonth);

    List<InvoiceStatistics> findByMonthRange(@Param("startMonth") String startMonth, @Param("endMonth") String endMonth);

    int incrementIssueCount(@Param("statMonth") String statMonth, @Param("amount") java.math.BigDecimal amount, @Param("tax") java.math.BigDecimal tax);

    int incrementVerifyCount(@Param("statMonth") String statMonth, @Param("passed") boolean passed);

    int incrementReimburseCount(@Param("statMonth") String statMonth, @Param("approved") boolean approved);
}
