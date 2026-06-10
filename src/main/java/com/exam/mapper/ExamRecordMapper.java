package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExamRecordMapper extends BaseMapper<ExamRecord> {
}
