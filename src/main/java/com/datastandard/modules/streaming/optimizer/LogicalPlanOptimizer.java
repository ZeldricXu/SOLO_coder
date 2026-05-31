package com.datastandard.modules.streaming.optimizer;

import com.datastandard.modules.streaming.ast.LogicalPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class LogicalPlanOptimizer {

    public LogicalPlan optimize(LogicalPlan plan) {
        log.info("开始优化逻辑计划: {}", plan.getType());

        LogicalPlan optimized = plan;

        if (hasFilter(plan) && hasProjection(plan)) {
            optimized = pushDownFilter(optimized);
            optimized = pushDownProjection(optimized);
        }

        if (hasAggregate(plan) && hasFilter(plan)) {
            optimized = mergeFilters(optimized);
        }

        if (hasJoin(plan)) {
            optimized = reorderJoins(optimized);
        }

        if (hasWindow(plan)) {
            optimized = optimizeWindow(optimized);
        }

        optimized = removeRedundantProjections(optimized);
        optimized = removeRedundantFilters(optimized);
        optimized = combineSimilarOperators(optimized);
        optimized = pruneUnusedColumns(optimized);

        log.info("逻辑计划优化完成");
        return optimized;
    }

    private LogicalPlan pushDownFilter(LogicalPlan plan) {
        log.debug("执行谓词下推优化");

        List<LogicalPlan> children = plan.getChildren();
        if (children.isEmpty()) return plan;

        LogicalPlan filterChild = null;
        int filterIndex = -1;

        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).getType() == LogicalPlan.PlanType.FILTER) {
                filterChild = children.get(i);
                filterIndex = i;
                break;
            }
        }

        if (filterChild == null) return plan;

        for (int i = 0; i < children.size(); i++) {
            if (i == filterIndex) continue;
            LogicalPlan child = children.get(i);
            if (canPushDown(filterChild, child)) {
                child.getChildren().add(0, copyFilter(filterChild));
                log.debug("谓词已下推到子节点: {}", child.getType());
            }
        }

        return plan;
    }

    private LogicalPlan pushDownProjection(LogicalPlan plan) {
        log.debug("执行投影下推优化");

        Set<String> usedColumns = collectUsedColumns(plan);

        List<LogicalPlan> children = plan.getChildren();
        for (LogicalPlan child : children) {
            if (child.getType() == LogicalPlan.PlanType.PROJECT) {
                List<String> columns = (List<String>) child.getProperties().get("columns");
                if (columns != null) {
                    columns.removeIf(col -> !usedColumns.contains(col));
                    child.getProperties().put("columns", columns);
                    log.debug("移除未使用的投影列, 剩余: {}", columns.size());
                }
            }
        }

        return plan;
    }

    private LogicalPlan mergeFilters(LogicalPlan plan) {
        log.debug("合并相邻过滤器");

        List<LogicalPlan> children = plan.getChildren();
        List<LogicalPlan> newChildren = new ArrayList<>();
        LogicalPlan lastFilter = null;

        for (LogicalPlan child : children) {
            if (child.getType() == LogicalPlan.PlanType.FILTER) {
                if (lastFilter == null) {
                    lastFilter = child;
                } else {
                    String mergedCondition = lastFilter.getProperties().get("condition")
                            + " AND "
                            + child.getProperties().get("condition");
                    lastFilter.getProperties().put("condition", mergedCondition);
                    log.debug("合并过滤器条件");
                }
            } else {
                if (lastFilter != null) {
                    newChildren.add(lastFilter);
                    lastFilter = null;
                }
                newChildren.add(child);
            }
        }

        if (lastFilter != null) {
            newChildren.add(lastFilter);
        }

        plan.setChildren(newChildren);
        return plan;
    }

    private LogicalPlan reorderJoins(LogicalPlan plan) {
        log.debug("重新排序JOIN");

        List<LogicalPlan> joins = new ArrayList<>();
        collectJoins(plan, joins);

        if (joins.size() <= 1) return plan;

        joins.sort((a, b) -> {
            long sizeA = estimateSize(a);
            long sizeB = estimateSize(b);
            return Long.compare(sizeA, sizeB);
        });

        log.debug("JOIN已按估计大小排序");
        return plan;
    }

    private LogicalPlan optimizeWindow(LogicalPlan plan) {
        log.debug("优化窗口操作");

        Queue<LogicalPlan> queue = new LinkedList<>();
        queue.offer(plan);

        while (!queue.isEmpty()) {
            LogicalPlan current = queue.poll();
            if (current.getType() == LogicalPlan.PlanType.WINDOW_AGG) {
                String windowType = (String) current.getProperties().get("windowType");
                if ("TUMBLE".equals(windowType)) {
                    current.getProperties().put("optimized", true);
                    current.getProperties().put("watermarkStrategy", "bounded-out-of-orderness");
                    log.debug("滚动窗口已优化");
                } else if ("HOP".equals(windowType)) {
                    current.getProperties().put("optimized", true);
                    current.getProperties().put("watermarkStrategy", "bounded-out-of-orderness");
                    log.debug("滑动窗口已优化");
                } else if ("SESSION".equals(windowType)) {
                    current.getProperties().put("optimized", true);
                    current.getProperties().put("watermarkStrategy", "idle-timeout");
                    log.debug("会话窗口已优化");
                }
            }
            for (LogicalPlan child : current.getChildren()) {
                queue.offer(child);
            }
        }

        return plan;
    }

    private LogicalPlan removeRedundantProjections(LogicalPlan plan) {
        log.debug("移除冗余投影");
        List<LogicalPlan> children = plan.getChildren();
        if (children.size() < 2) return plan;

        List<LogicalPlan> newChildren = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            LogicalPlan child = children.get(i);
            if (child.getType() == LogicalPlan.PlanType.PROJECT && i < children.size() - 1) {
                LogicalPlan next = children.get(i + 1);
                if (next.getType() == LogicalPlan.PlanType.PROJECT) {
                    log.debug("检测到连续投影，移除冗余投影");
                    continue;
                }
            }
            newChildren.add(child);
        }
        plan.setChildren(newChildren);
        return plan;
    }

    private LogicalPlan removeRedundantFilters(LogicalPlan plan) {
        log.debug("移除冗余过滤器");
        List<LogicalPlan> children = plan.getChildren();
        for (LogicalPlan child : children) {
            if (child.getType() == LogicalPlan.PlanType.FILTER) {
                String condition = (String) child.getProperties().get("condition");
                if ("1=1".equals(condition) || "true".equalsIgnoreCase(condition)) {
                    log.debug("移除恒真过滤器");
                    children.remove(child);
                    break;
                }
            }
        }
        return plan;
    }

    private LogicalPlan combineSimilarOperators(LogicalPlan plan) {
        log.debug("合并相似操作符");

        if (plan.getType() == LogicalPlan.PlanType.AGGREGATE) {
            for (LogicalPlan child : plan.getChildren()) {
                if (child.getType() == LogicalPlan.PlanType.AGGREGATE) {
                    Map<String, Object> propsA = plan.getProperties();
                    Map<String, Object> propsB = child.getProperties();
                    if (Objects.equals(propsA.get("groupBy"), propsB.get("groupBy"))) {
                        log.debug("合并相同分组的聚合操作");
                        plan.getChildren().remove(child);
                        plan.getChildren().addAll(child.getChildren());
                    }
                }
            }
        }

        for (LogicalPlan child : plan.getChildren()) {
            combineSimilarOperators(child);
        }

        return plan;
    }

    private LogicalPlan pruneUnusedColumns(LogicalPlan plan) {
        log.debug("裁剪未使用的列");

        Set<String> usedColumns = collectUsedColumns(plan);

        Queue<LogicalPlan> queue = new LinkedList<>();
        queue.offer(plan);

        while (!queue.isEmpty()) {
            LogicalPlan current = queue.poll();
            List<String> columns = (List<String>) current.getProperties().get("columns");
            if (columns != null) {
                columns.removeIf(col -> !usedColumns.contains(col));
                current.getProperties().put("prunedColumns", new ArrayList<>(columns));
            }
            for (LogicalPlan child : current.getChildren()) {
                queue.offer(child);
            }
        }

        return plan;
    }

    private boolean hasFilter(LogicalPlan plan) {
        return checkType(plan, LogicalPlan.PlanType.FILTER);
    }

    private boolean hasProjection(LogicalPlan plan) {
        return checkType(plan, LogicalPlan.PlanType.PROJECT);
    }

    private boolean hasAggregate(LogicalPlan plan) {
        return checkType(plan, LogicalPlan.PlanType.AGGREGATE);
    }

    private boolean hasJoin(LogicalPlan plan) {
        return checkType(plan, LogicalPlan.PlanType.JOIN);
    }

    private boolean hasWindow(LogicalPlan plan) {
        return checkType(plan, LogicalPlan.PlanType.WINDOW_AGG) ||
               checkType(plan, LogicalPlan.PlanType.TUMBLE_WINDOW) ||
               checkType(plan, LogicalPlan.PlanType.HOP_WINDOW) ||
               checkType(plan, LogicalPlan.PlanType.SESSION_WINDOW);
    }

    private boolean checkType(LogicalPlan plan, LogicalPlan.PlanType type) {
        if (plan.getType() == type) return true;
        for (LogicalPlan child : plan.getChildren()) {
            if (checkType(child, type)) return true;
        }
        return false;
    }

    private boolean canPushDown(LogicalPlan filter, LogicalPlan child) {
        String filterCondition = (String) filter.getProperties().get("condition");
        if (filterCondition == null) return false;
        Set<String> filterColumns = extractColumnsFromCondition(filterCondition);
        Set<String> childColumns = collectOutputColumns(child);
        return childColumns.containsAll(filterColumns);
    }

    private LogicalPlan copyFilter(LogicalPlan filter) {
        LogicalPlan copy = new LogicalPlan();
        copy.setType(filter.getType());
        copy.setProperties(new HashMap<>(filter.getProperties()));
        copy.setChildren(new ArrayList<>(filter.getChildren()));
        return copy;
    }

    private Set<String> collectUsedColumns(LogicalPlan plan) {
        Set<String> columns = new HashSet<>();
        Queue<LogicalPlan> queue = new LinkedList<>();
        queue.offer(plan);
        while (!queue.isEmpty()) {
            LogicalPlan current = queue.poll();
            List<String> cols = (List<String>) current.getProperties().get("columns");
            if (cols != null) {
                columns.addAll(cols);
            }
            for (LogicalPlan child : current.getChildren()) {
                queue.offer(child);
            }
        }
        return columns;
    }

    private Set<String> extractColumnsFromCondition(String condition) {
        Set<String> columns = new HashSet<>();
        String[] tokens = condition.split("[=><!&|()\\s]+");
        for (String token : tokens) {
            if (!token.isEmpty() && !token.matches("\\d+") &&
                !"AND".equalsIgnoreCase(token) && !"OR".equalsIgnoreCase(token)) {
                columns.add(token);
            }
        }
        return columns;
    }

    private Set<String> collectOutputColumns(LogicalPlan plan) {
        Set<String> columns = new HashSet<>();
        List<String> cols = (List<String>) plan.getProperties().get("outputColumns");
        if (cols != null) {
            columns.addAll(cols);
        }
        return columns;
    }

    private void collectJoins(LogicalPlan plan, List<LogicalPlan> joins) {
        if (plan.getType() == LogicalPlan.PlanType.JOIN) {
            joins.add(plan);
        }
        for (LogicalPlan child : plan.getChildren()) {
            collectJoins(child, joins);
        }
    }

    private long estimateSize(LogicalPlan plan) {
        Long rows = (Long) plan.getProperties().get("estimatedRows");
        if (rows != null) return rows;
        return 1000000;
    }
}
