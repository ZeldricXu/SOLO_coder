package com.exam.service.constraint;

import com.exam.entity.Question;

import java.util.Arrays;

public class KnowledgePointLimitConstraint implements ConstraintSolver {

    private final int maxQuestionsPerPoint;

    public KnowledgePointLimitConstraint(int maxQuestionsPerPoint) {
        this.maxQuestionsPerPoint = maxQuestionsPerPoint;
    }

    @Override
    public String getName() {
        return "KNOWLEDGE_POINT_LIMIT";
    }

    @Override
    public boolean check(Question question, SelectionContext context) {
        String kps = question.getKnowledgePoints();
        if (kps == null || kps.isEmpty()) return true;

        return Arrays.stream(kps.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .allMatch(kp -> {
                    int count = context.getKnowledgePointCount().getOrDefault(kp, 0);
                    return count < maxQuestionsPerPoint;
                });
    }

    @Override
    public void onSelected(Question question, SelectionContext context) {
        String kps = question.getKnowledgePoints();
        if (kps == null || kps.isEmpty()) return;

        Arrays.stream(kps.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(kp -> context.getKnowledgePointCount().merge(kp, 1, Integer::sum));
    }

    @Override
    public boolean isSatisfied(SelectionContext context) {
        return context.getSelectedCount() >= context.getTargetCount();
    }

    @Override
    public int getPriority() {
        return 80;
    }
}
