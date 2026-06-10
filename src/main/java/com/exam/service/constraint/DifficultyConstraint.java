package com.exam.service.constraint;

import com.exam.common.Constants;
import com.exam.entity.Question;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DifficultyConstraint implements ConstraintSolver {

    private final BigDecimal easyRatio;
    private final BigDecimal mediumRatio;
    private final BigDecimal hardRatio;
    private final Integer questionType;

    public DifficultyConstraint(BigDecimal easyRatio, BigDecimal mediumRatio, BigDecimal hardRatio, Integer questionType) {
        this.easyRatio = easyRatio != null ? easyRatio : new BigDecimal("0.3");
        this.mediumRatio = mediumRatio != null ? mediumRatio : new BigDecimal("0.5");
        this.hardRatio = hardRatio != null ? hardRatio : new BigDecimal("0.2");
        this.questionType = questionType;
    }

    @Override
    public String getName() {
        return "DISTRIBUTION_DIFFICULTY";
    }

    @Override
    public boolean check(Question question, SelectionContext context) {
        if (question.getDifficulty() == null) return true;

        int total = context.getTargetCount();
        int selectedCount = context.getSelectedCount();
        if (selectedCount >= total) return false;

        int targetEasy = new BigDecimal(total).multiply(easyRatio).setScale(0, RoundingMode.HALF_UP).intValue();
        int targetMedium = new BigDecimal(total).multiply(mediumRatio).setScale(0, RoundingMode.HALF_UP).intValue();
        int targetHard = total - targetEasy - targetMedium;

        int currentEasy = context.getDifficultyCount().getOrDefault(Constants.DIFFICULTY_EASY, 0);
        int currentMedium = context.getDifficultyCount().getOrDefault(Constants.DIFFICULTY_MEDIUM, 0);
        int currentHard = context.getDifficultyCount().getOrDefault(Constants.DIFFICULTY_HARD, 0);

        int diff = question.getDifficulty();
        if (diff == Constants.DIFFICULTY_EASY) {
            return currentEasy < targetEasy;
        } else if (diff == Constants.DIFFICULTY_MEDIUM) {
            return currentMedium < targetMedium;
        } else if (diff == Constants.DIFFICULTY_HARD) {
            return currentHard < targetHard;
        }
        return true;
    }

    @Override
    public void onSelected(Question question, SelectionContext context) {
        if (question.getDifficulty() != null) {
            int diff = question.getDifficulty();
            context.getDifficultyCount().merge(diff, 1, Integer::sum);
        }
    }

    @Override
    public boolean isSatisfied(SelectionContext context) {
        return context.getSelectedCount() >= context.getTargetCount();
    }

    @Override
    public int getPriority() {
        return 100;
    }

    public Integer getQuestionType() {
        return questionType;
    }
}
