package com.exam.service.constraint;

import com.exam.entity.Question;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class ExcludedQuestionConstraint implements ConstraintSolver {

    private final Set<Long> excludedIds;

    public ExcludedQuestionConstraint(Collection<Long> excludedIds) {
        this.excludedIds = new HashSet<>(excludedIds);
    }

    @Override
    public String getName() {
        return "EXCLUDED_QUESTIONS";
    }

    @Override
    public boolean check(Question question, SelectionContext context) {
        if (excludedIds.isEmpty()) return true;
        if (question.getId() == null) return true;
        return !excludedIds.contains(question.getId())
                && !context.getExcludedQuestionIds().contains(question.getId());
    }

    @Override
    public void onSelected(Question question, SelectionContext context) {
        if (question.getId() != null) {
            context.addExcludedQuestionId(question.getId());
        }
    }

    @Override
    public boolean isSatisfied(SelectionContext context) {
        return context.getSelectedCount() >= context.getTargetCount();
    }

    @Override
    public int getPriority() {
        return 200;
    }
}
