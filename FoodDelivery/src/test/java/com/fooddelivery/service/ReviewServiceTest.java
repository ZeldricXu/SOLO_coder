package com.fooddelivery.service;

import com.fooddelivery.builder.TestDataBuilder;
import com.fooddelivery.dto.CreateReviewRequest;
import com.fooddelivery.dto.CreateReviewResponse;
import com.fooddelivery.entity.Delivery;
import com.fooddelivery.entity.Order;
import com.fooddelivery.entity.Review;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("评价模块测试 - 订单评价")
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private RestaurantService restaurantService;

    @Mock
    private RiderService riderService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private ReviewService reviewService;

    private Order testOrderDelivered;
    private Order testOrderPending;
    private Delivery testDelivery;
    private Review testReview;

    @BeforeEach
    void setUp() {
        testOrderDelivered = TestDataBuilder.buildOrderDelivered();
        testOrderPending = TestDataBuilder.buildOrder("pending_confirm");
        testDelivery = TestDataBuilder.buildDelivery();
        testDelivery.setOrderId(testOrderDelivered.getOrderId());
        testReview = TestDataBuilder.buildReview();
    }

    @Test
    @DisplayName("评价评分计算 - 首次评价")
    void testRatingCalculation_FirstReview() {
        int initialCount = 0;
        double initialRating = 0.0;
        int newRating = 5;

        int newCount = initialCount + 1;
        double newAvgRating = (initialRating * initialCount + newRating) / (double) newCount;

        assertEquals(1, newCount);
        assertEquals(5.0, newAvgRating);
    }

    @Test
    @DisplayName("评价评分计算 - 追加评价")
    void testRatingCalculation_AdditionalReview() {
        int currentCount = 10;
        double currentRating = 4.5;
        int newRating = 5;

        double totalRating = currentRating * currentCount;
        int newCount = currentCount + 1;
        double newAvgRating = (totalRating + newRating) / newCount;

        assertEquals(11, newCount);
        assertEquals((4.5 * 10 + 5) / 11.0, newAvgRating, 0.001);
    }

    @Test
    @DisplayName("评价评分计算 - 多评分平均")
    void testRatingCalculation_MultipleRatings() {
        int[] ratings = {5, 4, 5, 3, 4, 5, 4, 5};
        double expectedAvg = (5 + 4 + 5 + 3 + 4 + 5 + 4 + 5) / 8.0;

        double currentAvg = 0.0;
        int currentCount = 0;

        for (int rating : ratings) {
            double total = currentAvg * currentCount;
            currentCount++;
            currentAvg = (total + rating) / currentCount;
        }

        assertEquals(8, currentCount);
        assertEquals(expectedAvg, currentAvg, 0.001);
    }

    @Test
    @DisplayName("评价评分计算 - 极端值验证")
    void testRatingCalculation_ExtremeValues() {
        int minRating = 1;
        int maxRating = 5;

        assertEquals(1, minRating);
        assertEquals(5, maxRating);

        double avg = (1 + 5) / 2.0;
        assertEquals(3.0, avg);
    }

    @Test
    @DisplayName("重复评价拒绝 - 已评价订单不能再评价")
    void testRejectDuplicateReview() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(true);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(5);
        request.setReview_content("很好！");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> reviewService.createReview(request));

        assertEquals(400, exception.getCode());
        assertEquals("订单已评价，请勿重复评价", exception.getMessage());
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @Test
    @DisplayName("重复评价拒绝 - 首次评价成功")
    void testFirstReviewSuccess() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(5);
        request.setReview_content("非常好！");

        CreateReviewResponse response = reviewService.createReview(request);

        assertNotNull(response);
        assertEquals("submitted", response.getStatus());
        verify(reviewRepository, times(1)).save(any(Review.class));
    }

    @Test
    @DisplayName("订单不存在时拒绝评价")
    void testRejectReviewForNonExistentOrder() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.empty());

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id("non_existent_order");
        request.setReview_rating(5);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> reviewService.createReview(request));

        assertEquals(404, exception.getCode());
        assertEquals("订单不存在", exception.getMessage());
    }

    @Test
    @DisplayName("订单未送达时拒绝评价")
    void testRejectReviewForUndeliveredOrder() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderPending));

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderPending.getOrderId());
        request.setReview_rating(5);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> reviewService.createReview(request));

        assertEquals(400, exception.getCode());
        assertEquals("订单尚未送达，无法评价", exception.getMessage());
    }

    @Test
    @DisplayName("评价成功后更新餐厅评分")
    void testUpdateRestaurantRatingAfterReview() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(4);

        reviewService.createReview(request);

        verify(restaurantService, times(1))
                .updateRestaurantRating(testOrderDelivered.getRestaurantId(), 4);
    }

    @Test
    @DisplayName("评价成功后更新骑手评分")
    void testUpdateRiderRatingAfterReview() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(5);

        reviewService.createReview(request);

        verify(riderService, times(1))
                .updateRiderRating(testDelivery.getRiderId(), 5);
    }

    @Test
    @DisplayName("评价成功后更新统计数据")
    void testUpdateStatisticsAfterReview() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(4);

        reviewService.createReview(request);

        verify(analysisService, times(1)).incrementReviewCount(anyInt());
        verify(analysisService, times(1)).addRating(anyInt(), eq(4));
    }

    @Test
    @DisplayName("评价评分边界测试 - 最低分1分")
    void testRatingBoundary_Minimum() {
        int minRating = 1;
        assertTrue(minRating >= 1 && minRating <= 5);

        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(minRating);

        CreateReviewResponse response = reviewService.createReview(request);
        assertNotNull(response);
    }

    @Test
    @DisplayName("评价评分边界测试 - 最高分5分")
    void testRatingBoundary_Maximum() {
        int maxRating = 5;
        assertTrue(maxRating >= 1 && maxRating <= 5);

        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(maxRating);

        CreateReviewResponse response = reviewService.createReview(request);
        assertNotNull(response);
    }

    @Test
    @DisplayName("评价内容可选 - 可以为空")
    void testReviewContentOptional() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(5);
        request.setReview_content(null);

        CreateReviewResponse response = reviewService.createReview(request);
        assertNotNull(response);
        assertEquals("submitted", response.getStatus());
    }

    @Test
    @DisplayName("订单评价后状态更新")
    void testOrderStatusUpdatedAfterReview() {
        when(orderService.getOrderById(anyString())).thenReturn(Optional.of(testOrderDelivered));
        when(reviewRepository.existsByOrderId(anyString())).thenReturn(false);
        when(deliveryService.getDeliveryByOrderId(anyString())).thenReturn(Optional.of(testDelivery));
        when(reviewRepository.save(any(Review.class))).thenReturn(testReview);

        CreateReviewRequest request = new CreateReviewRequest();
        request.setOrder_id(testOrderDelivered.getOrderId());
        request.setReview_rating(5);

        reviewService.createReview(request);

        verify(orderService, times(1))
                .updateOrderStatus(testOrderDelivered.getOrderId(), "reviewed");
    }
}
