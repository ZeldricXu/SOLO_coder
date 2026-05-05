package com.orderflow.service;

import com.orderflow.entity.Order;
import com.orderflow.enums.OrderStatus;
import com.orderflow.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    @Autowired
    private OrderRepository orderRepository;

    public Map<String, Object> getOrderStatistics() {
        logger.info("获取订单统计数据");

        Map<String, Object> statistics = new HashMap<>();

        long totalOrders = orderRepository.count();
        statistics.put("totalOrders", totalOrders);

        long pendingPayment = orderRepository.countByStatus(OrderStatus.PENDING_PAYMENT);
        long paid = orderRepository.countByStatus(OrderStatus.PAID);
        long shipped = orderRepository.countByStatus(OrderStatus.SHIPPED);
        long completed = orderRepository.countByStatus(OrderStatus.COMPLETED);
        long cancelled = orderRepository.countByStatus(OrderStatus.CANCELLED);
        long refunding = orderRepository.countByStatus(OrderStatus.REFUNDING);
        long refunded = orderRepository.countByStatus(OrderStatus.REFUNDED);

        Map<String, Long> statusCounts = new HashMap<>();
        statusCounts.put(OrderStatus.PENDING_PAYMENT.getCode(), pendingPayment);
        statusCounts.put(OrderStatus.PAID.getCode(), paid);
        statusCounts.put(OrderStatus.SHIPPED.getCode(), shipped);
        statusCounts.put(OrderStatus.COMPLETED.getCode(), completed);
        statusCounts.put(OrderStatus.CANCELLED.getCode(), cancelled);
        statusCounts.put(OrderStatus.REFUNDING.getCode(), refunding);
        statusCounts.put(OrderStatus.REFUNDED.getCode(), refunded);
        statistics.put("statusCounts", statusCounts);

        BigDecimal totalSales = calculateTotalSales();
        statistics.put("totalSales", totalSales);

        BigDecimal todaySales = calculateTodaySales();
        statistics.put("todaySales", todaySales);

        long todayOrders = countTodayOrders();
        statistics.put("todayOrders", todayOrders);

        return statistics;
    }

    private BigDecimal calculateTotalSales() {
        BigDecimal paidAmount = orderRepository.sumTotalAmountByStatus(OrderStatus.PAID);
        BigDecimal shippedAmount = orderRepository.sumTotalAmountByStatus(OrderStatus.SHIPPED);
        BigDecimal completedAmount = orderRepository.sumTotalAmountByStatus(OrderStatus.COMPLETED);

        return paidAmount.add(shippedAmount).add(completedAmount);
    }

    private BigDecimal calculateTodaySales() {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime todayEnd = LocalDateTime.now();

        Specification<Order> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.between(root.get("createdAt"), todayStart, todayEnd));
            predicates.add(criteriaBuilder.in(root.get("status"))
                    .value(OrderStatus.PAID)
                    .value(OrderStatus.SHIPPED)
                    .value(OrderStatus.COMPLETED));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        List<Order> orders = orderRepository.findAll(spec);
        return orders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long countTodayOrders() {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime todayEnd = LocalDateTime.now();

        Specification<Order> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.between(root.get("createdAt"), todayStart, todayEnd));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return orderRepository.count(spec);
    }

    public List<Map<String, Object>> getRecentOrders(int limit) {
        logger.info("获取最近订单，数量: {}", limit);

        PageRequest pageRequest = PageRequest.of(0, Math.min(limit, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Order> page = orderRepository.findAll(pageRequest);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : page.getContent()) {
            Map<String, Object> orderMap = new HashMap<>();
            orderMap.put("orderId", order.getOrderId());
            orderMap.put("orderNo", order.getOrderNo());
            orderMap.put("userId", order.getUserId());
            orderMap.put("status", order.getStatus().getCode());
            orderMap.put("totalAmount", order.getTotalAmount());
            orderMap.put("createdAt", formatDateTime(order.getCreatedAt()));
            result.add(orderMap);
        }

        return result;
    }

    public Map<String, Object> getDailyStatistics(LocalDateTime startDate, LocalDateTime endDate) {
        logger.info("获取指定日期范围的统计数据，开始日期: {}, 结束日期: {}", startDate, endDate);

        Specification<Order> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.between(root.get("createdAt"), startDate, endDate));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        List<Order> orders = orderRepository.findAll(spec);

        Map<String, Object> result = new HashMap<>();
        result.put("startDate", formatDateTime(startDate));
        result.put("endDate", formatDateTime(endDate));
        result.put("orderCount", (long) orders.size());

        BigDecimal totalAmount = orders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.put("totalAmount", totalAmount);

        Map<String, Long> statusBreakdown = new HashMap<>();
        for (Order order : orders) {
            String statusCode = order.getStatus().getCode();
            statusBreakdown.put(statusCode, statusBreakdown.getOrDefault(statusCode, 0L) + 1);
        }
        result.put("statusBreakdown", statusBreakdown);

        return result;
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dateTime.format(formatter);
    }
}
