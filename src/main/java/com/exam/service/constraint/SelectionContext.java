package com.exam.service.constraint;

import com.exam.entity.Question;

import java.math.BigDecimal;
import java.util.*;

public class SelectionContext {

    private final Long subjectId;
    private final Integer targetCount;
    private final List<Question> selected = new ArrayList<>();
    private final Map<Integer, Integer> difficultyCount = new HashMap<>();
    private final Map<String, Integer> knowledgePointCount = new HashMap<>();
    private final Set<Long> excludedQuestionIds = new HashSet<>();
    private final Map<String, Object> attributes = new HashMap<>();

    public SelectionContext(Long subjectId, Integer targetCount) {
        this.subjectId = subjectId;
        this.targetCount = targetCount;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public List<Question> getSelected() {
        return Collections.unmodifiableList(selected);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void addSelected(Question question) {
        selected.add(question);
    }

    public Map<Integer, Integer> getDifficultyCount() {
        return difficultyCount;
    }

    public Map<String, Integer> getKnowledgePointCount() {
        return knowledgePointCount;
    }

    public Set<Long> getExcludedQuestionIds() {
        return excludedQuestionIds;
    }

    public void addExcludedQuestionId(Long id) {
        excludedQuestionIds.add(id);
    }

    public void addExcludedQuestionIds(Collection<Long> ids) {
        excludedQuestionIds.addAll(ids);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
