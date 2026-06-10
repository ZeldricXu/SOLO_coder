package com.exam.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.common.Result;
import com.exam.dto.PaperGenerateDTO;
import com.exam.entity.Paper;
import com.exam.entity.PaperQuestion;
import com.exam.entity.Question;
import com.exam.service.PaperService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/teacher/papers")
@RequiredArgsConstructor
public class PaperController {

    private final PaperService paperService;

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<IPage<Paper>> getPaperPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String keyword) {
        return Result.success(paperService.getPaperPage(pageNum, pageSize, subjectId, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Paper> getPaperById(@PathVariable Long id) {
        return Result.success(paperService.getPaperById(id));
    }

    @GetMapping("/{id}/questions")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<List<PaperQuestion>> getPaperQuestions(@PathVariable Long id) {
        return Result.success(paperService.getPaperQuestions(id));
    }

    @PostMapping("/generate/random")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Paper> generateRandomPaper(@RequestBody PaperGenerateDTO generateDTO) {
        return Result.success(paperService.generateRandomPaper(generateDTO));
    }

    @PostMapping("/generate/ab")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Map<String, Paper>> generateABPaper(@RequestBody PaperGenerateDTO generateDTO) {
        return Result.success(paperService.generateABPaper(generateDTO));
    }

    @PostMapping("/fixed")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Paper> createFixedPaper(@RequestBody Paper paper,
                                          @RequestParam List<Long> questionIds) {
        return Result.success(paperService.createFixedPaper(paper, questionIds));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Paper> updatePaper(@RequestBody Paper paper) {
        return Result.success(paperService.updatePaper(paper));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> deletePaper(@PathVariable Long id) {
        paperService.deletePaper(id);
        return Result.success();
    }

    @PostMapping("/{id}/copy")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> copyPaper(@PathVariable Long id, @RequestParam String newName) {
        paperService.copyPaper(id, newName);
        return Result.success();
    }
}
