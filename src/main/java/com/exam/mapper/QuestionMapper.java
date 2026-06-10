package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.entity.Question;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface QuestionMapper extends BaseMapper<Question> {

    @Select("SELECT * FROM exam_question WHERE subject_id = #{subjectId} " +
            "AND question_type = #{questionType} AND difficulty = #{difficulty} " +
            "AND deleted = 0 ORDER BY RANDOM() LIMIT #{limit}")
    List<Question> selectRandomQuestions(@Param("subjectId") Long subjectId,
                                         @Param("questionType") Integer questionType,
                                         @Param("difficulty") Integer difficulty,
                                         @Param("limit") Integer limit);

    @Select("SELECT * FROM exam_question WHERE subject_id = #{subjectId} " +
            "AND question_type = #{questionType} AND deleted = 0 ORDER BY RANDOM() LIMIT #{limit}")
    List<Question> selectRandomQuestionsByType(@Param("subjectId") Long subjectId,
                                               @Param("questionType") Integer questionType,
                                               @Param("limit") Integer limit);

    @Select("SELECT COUNT(*) FROM exam_question WHERE subject_id = #{subjectId} " +
            "AND question_type = #{questionType} AND difficulty = #{difficulty} AND deleted = 0")
    Integer countByTypeAndDifficulty(@Param("subjectId") Long subjectId,
                                     @Param("questionType") Integer questionType,
                                     @Param("difficulty") Integer difficulty);

    @Select("SELECT COUNT(*) FROM exam_question WHERE subject_id = #{subjectId} " +
            "AND question_type = #{questionType} AND deleted = 0")
    Integer countByType(@Param("subjectId") Long subjectId,
                        @Param("questionType") Integer questionType);

    IPage<Question> selectPageWithConditions(Page<Question> page,
                                             @Param("subjectId") Long subjectId,
                                             @Param("questionType") Integer questionType,
                                             @Param("difficulty") Integer difficulty,
                                             @Param("keyword") String keyword);
}
