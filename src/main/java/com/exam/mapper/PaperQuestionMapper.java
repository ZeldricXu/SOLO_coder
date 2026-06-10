package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.PaperQuestion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PaperQuestionMapper extends BaseMapper<PaperQuestion> {

    @Select("SELECT * FROM exam_paper_question WHERE paper_id = #{paperId} AND deleted = 0 ORDER BY question_order")
    List<PaperQuestion> selectByPaperId(@Param("paperId") Long paperId);

    @Select("SELECT * FROM exam_paper_question WHERE paper_id = #{paperId} AND question_id = #{questionId} AND deleted = 0 LIMIT 1")
    PaperQuestion selectByPaperAndQuestion(@Param("paperId") Long paperId, @Param("questionId") Long questionId);
}
