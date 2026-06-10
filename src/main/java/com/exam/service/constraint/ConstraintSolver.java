package com.exam.service.constraint;

import com.exam.entity.Question;

import java.util.List;

public interface ConstraintSolver {

    String getName();

    boolean check(Question question, SelectionContext context);

    void onSelected(Question question, SelectionContext context);

    boolean isSatisfied(SelectionContext context);

    default int getPriority() {
        return 0;
    }
}
