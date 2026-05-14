package com.fooddelivery.service;

import com.fooddelivery.dto.CreateReviewRequest;
import com.fooddelivery.dto.CreateReviewResponse;
import com.fooddelivery.entity.Delivery;
import com.fooddelivery.entity.Order;
import com.fooddelivery.entity.Review;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.ReviewRepository;
import com.fooddelivery.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private RiderService riderService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public CreateReviewResponse createReview(CreateReviewRequest request) {
        Order order = orderService.getOrderById(request.getOrder_id())
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));

        if (!"delivered".equals(order.getOrderStatus())) {
            throw new BusinessException(400, "订单尚未送达，无法评价");
        }

        if (reviewRepository.existsByOrderId(request.getOrder_id())) {
            throw new BusinessException(400, "订单已评价，请勿重复评价");
        }

        Delivery delivery = deliveryService.getDeliveryByOrderId(request.getOrder_id())
                .orElseThrow(() -> new RuntimeException("配送任务不存在"));

        Review review = new Review();
        review.setReviewId(IdGenerator.generateReviewId());
        review.setOrderId(request.getOrder_id());
        review.setUserId(request.getUser_id() != null ? request.getUser_id() : order.getUserId());
        review.setRestaurantId(order.getRestaurantId());
        review.setRiderId(delivery.getRiderId());
        review.setReviewRating(request.getReview_rating());
        review.setReviewContent(request.getReview_content());
        Review saved = reviewRepository.save(review);

        restaurantService.updateRestaurantRating(order.getRestaurantId(), request.getReview_rating());
        riderService.updateRiderRating(delivery.getRiderId(), request.getReview_rating());

        order.setHasReview(true);
        orderService.updateOrderStatus(order.getOrderId(), "reviewed");

        int month = java.time.LocalDateTime.now().getMonth().getValue();
        analysisService.incrementReviewCount(month);
        analysisService.addRating(month, request.getReview_rating());

        historyService.recordHistory("review", saved.getReviewId(), "create",
                "创建评价，评分：" + request.getReview_rating());

        return new CreateReviewResponse(saved.getReviewId(), "submitted");
    }

    public Optional<Review> getReviewById(String reviewId) {
        return reviewRepository.findByReviewId(reviewId);
    }

    public Optional<Review> getReviewByOrderId(String orderId) {
        return reviewRepository.findByOrderId(orderId);
    }

    public List<Review> getReviewsByRestaurantId(String restaurantId) {
        return reviewRepository.findByRestaurantId(restaurantId);
    }

    public List<Review> getReviewsByRiderId(String riderId) {
        return reviewRepository.findByRiderId(riderId);
    }

    public List<Review> getReviewsByUserId(String userId) {
        return reviewRepository.findByUserId(userId);
    }
}
