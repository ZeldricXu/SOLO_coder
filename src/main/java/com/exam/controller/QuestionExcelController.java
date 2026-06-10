package com.exam.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.exam.common.BusinessException;
import com.exam.common.Result;
import com.exam.dto.QuestionDTO;
import com.exam.service.QuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/teacher/questions/excel")
@RequiredArgsConstructor
public class QuestionExcelController {

    private final QuestionService questionService;

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<String> importQuestions(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("请选择上传文件");
        }

        try {
            List<QuestionExcelDTO> excelDataList = new ArrayList<>();

            EasyExcel.read(file.getInputStream(), QuestionExcelDTO.class,
                    new PageReadListener<QuestionExcelDTO>(dataList -> {
                        excelDataList.addAll(dataList);
                    })).sheet().doRead();

            List<QuestionDTO> questionDTOList = convertExcelToDTO(excelDataList);
            questionService.importQuestions(questionDTOList);

            return Result.success("导入成功，共" + questionDTOList.size() + "道题目");
        } catch (Exception e) {
            throw new BusinessException("导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public ResponseEntity<byte[]> exportQuestions(@RequestParam List<Long> ids) {
        try {
            List<QuestionDTO> questions = questionService.exportQuestions(ids);
            List<QuestionExcelDTO> excelDataList = convertDTOToExcel(questions);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            EasyExcel.write(outputStream, QuestionExcelDTO.class)
                    .sheet("题目")
                    .doWrite(excelDataList);

            byte[] content = outputStream.toByteArray();
            String fileName = URLEncoder.encode("题目导出.xlsx", StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + fileName)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(content);
        } catch (Exception e) {
            throw new BusinessException("导出失败: " + e.getMessage());
        }
    }

    private List<QuestionDTO> convertExcelToDTO(List<QuestionExcelDTO> excelList) {
        List<QuestionDTO> result = new ArrayList<>();
        for (QuestionExcelDTO excel : excelList) {
            QuestionDTO dto = new QuestionDTO();
            dto.setQuestionTitle(excel.getTitle());
            dto.setQuestionContent(excel.getContent());
            dto.setQuestionType(excel.getType());
            dto.setDifficulty(excel.getDifficulty());
            dto.setDefaultScore(excel.getScore());
            dto.setAnswer(excel.getAnswer());
            dto.setAnalysis(excel.getAnalysis());
            dto.setProgrammingLanguage(excel.getProgrammingLanguage());
            dto.setTestCases(excel.getTestCases());
            dto.setTimeLimit(excel.getTimeLimit());
            dto.setMemoryLimit(excel.getMemoryLimit());

            List<QuestionDTO.QuestionOptionDTO> options = new ArrayList<>();
            if (excel.getOptionA() != null) {
                QuestionDTO.QuestionOptionDTO opt = new QuestionDTO.QuestionOptionDTO();
                opt.setOptionLabel("A");
                opt.setOptionContent(excel.getOptionA());
                options.add(opt);
            }
            if (excel.getOptionB() != null) {
                QuestionDTO.QuestionOptionDTO opt = new QuestionDTO.QuestionOptionDTO();
                opt.setOptionLabel("B");
                opt.setOptionContent(excel.getOptionB());
                options.add(opt);
            }
            if (excel.getOptionC() != null) {
                QuestionDTO.QuestionOptionDTO opt = new QuestionDTO.QuestionOptionDTO();
                opt.setOptionLabel("C");
                opt.setOptionContent(excel.getOptionC());
                options.add(opt);
            }
            if (excel.getOptionD() != null) {
                QuestionDTO.QuestionOptionDTO opt = new QuestionDTO.QuestionOptionDTO();
                opt.setOptionLabel("D");
                opt.setOptionContent(excel.getOptionD());
                options.add(opt);
            }
            dto.setOptions(options);

            result.add(dto);
        }
        return result;
    }

    private List<QuestionExcelDTO> convertDTOToExcel(List<QuestionDTO> dtoList) {
        List<QuestionExcelDTO> result = new ArrayList<>();
        for (QuestionDTO dto : dtoList) {
            QuestionExcelDTO excel = new QuestionExcelDTO();
            excel.setTitle(dto.getQuestionTitle());
            excel.setContent(dto.getQuestionContent());
            excel.setType(dto.getQuestionType());
            excel.setDifficulty(dto.getDifficulty());
            excel.setScore(dto.getDefaultScore());
            excel.setAnswer(dto.getAnswer());
            excel.setAnalysis(dto.getAnalysis());
            excel.setProgrammingLanguage(dto.getProgrammingLanguage());
            excel.setTestCases(dto.getTestCases());
            excel.setTimeLimit(dto.getTimeLimit());
            excel.setMemoryLimit(dto.getMemoryLimit());

            if (dto.getOptions() != null) {
                for (QuestionDTO.QuestionOptionDTO opt : dto.getOptions()) {
                    switch (opt.getOptionLabel()) {
                        case "A":
                            excel.setOptionA(opt.getOptionContent());
                            break;
                        case "B":
                            excel.setOptionB(opt.getOptionContent());
                            break;
                        case "C":
                            excel.setOptionC(opt.getOptionContent());
                            break;
                        case "D":
                            excel.setOptionD(opt.getOptionContent());
                            break;
                        default:
                            break;
                    }
                }
            }

            result.add(excel);
        }
        return result;
    }
}
