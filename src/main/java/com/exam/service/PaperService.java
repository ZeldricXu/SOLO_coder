package com.exam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.dto.PaperGenerateDTO;
import com.exam.entity.Paper;
import com.exam.entity.PaperQuestion;
import com.exam.entity.Question;

import java.util.List;
import java.util.Map;

public interface PaperService {
    IPage<Paper> getPaperPage(int pageNum, int pageSize, Long subjectId, String keyword);

    Paper getPaperById(Long id);

    List<PaperQuestion> getPaperQuestions(Long paperId);

    Paper generatePaper(PaperGenerateDTO generateDTO);

    Paper generateRandomPaper(PaperGenerateDTO generateDTO);

    Map<String, Paper> generateABPaper(PaperGenerateDTO generateDTO);

    Paper createFixedPaper(Paper paper, List<Long> questionIds);

    void deletePaper(Long id);

    Paper updatePaper(Paper paper);

    void copyPaper(Long sourcePaperId, String newPaperName);
}
