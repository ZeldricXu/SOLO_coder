package com.exam.service.constraint;

import com.exam.entity.Question;

import java.util.*;

public class ConstraintChain {

    private final List<ConstraintSolver> solvers = new ArrayList<>();

    public ConstraintChain addSolver(ConstraintSolver solver) {
        solvers.add(solver);
        solvers.sort(Comparator.comparingInt(ConstraintSolver::getPriority).reversed());
        return this;
    }

    public boolean checkAll(Question question, SelectionContext context) {
        for (ConstraintSolver solver : solvers) {
            if (!solver.check(question, context)) {
                return false;
            }
        }
        return true;
    }

    public void notifySelected(Question question, SelectionContext context) {
        for (ConstraintSolver solver : solvers) {
            solver.onSelected(question, context);
        }
    }

    public boolean isFullySatisfied(SelectionContext context) {
        for (ConstraintSolver solver : solvers) {
            if (!solver.isSatisfied(context)) {
                return false;
            }
        }
        return true;
    }

    public List<ConstraintSolver> getSolvers() {
        return Collections.unmodifiableList(solvers);
    }

    public int size() {
        return solvers.size();
    }
}
