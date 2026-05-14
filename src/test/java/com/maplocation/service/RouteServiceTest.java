package com.maplocation.service;

import com.maplocation.builder.TestDataBuilder;
import com.maplocation.dto.RoutePlanRequest;
import com.maplocation.dto.RoutePlanResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Route;
import com.maplocation.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private AsyncRouteTaskQueue taskQueue;

    @Mock
    private RouteTaskWorker taskWorker;

    @InjectMocks
    private RouteService routeService;

    private RoutePlanRequest drivingRequest;
    private RoutePlanRequest walkingRequest;
    private RoutePlanRequest transitRequest;

    @BeforeEach
    void setUp() {
        drivingRequest = TestDataBuilder.buildDrivingRouteRequest();
        walkingRequest = TestDataBuilder.buildWalkingRouteRequest();
        transitRequest = TestDataBuilder.buildTransitRouteRequest();
    }

    @Test
    @DisplayName("测试同步路径规划 - 驾车路径")
    void testPlanRouteSync_Driving() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutePlanResponse response = routeService.planRouteSync(drivingRequest);

        assertNotNull(response);
        assertNotNull(response.getRouteId());
        assertTrue(response.getRouteId().startsWith("route_"));
        assertEquals("driving", response.getRouteType());
        assertTrue(response.getRouteDistance() > 0);
        assertTrue(response.getRouteDuration() > 0);
        assertNotNull(response.getRoutePath());
        assertTrue(response.getRoutePath().size() >= 2);

        verify(routeRepository, times(1)).save(any(Route.class));
        verify(analysisService, times(1)).incrementRouteCount();
        verify(analysisService, times(1)).updateAvgDistance(anyDouble());
    }

    @Test
    @DisplayName("测试同步路径规划 - 步行路径")
    void testPlanRouteSync_Walking() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutePlanResponse response = routeService.planRouteSync(walkingRequest);

        assertNotNull(response);
        assertEquals("walking", response.getRouteType());
        assertTrue(response.getRouteDistance() > 0);
        assertTrue(response.getRouteDuration() > 0);
    }

    @Test
    @DisplayName("测试同步路径规划 - 公交路径")
    void testPlanRouteSync_Transit() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutePlanResponse response = routeService.planRouteSync(transitRequest);

        assertNotNull(response);
        assertEquals("transit", response.getRouteType());
        assertTrue(response.getRouteDistance() > 0);
        assertTrue(response.getRouteDuration() > 0);
    }

    @Test
    @DisplayName("测试不同路径类型的距离系数 - 驾车距离最大")
    void testRouteDistance_DifferentRouteTypes() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        Coordinates start = TestDataBuilder.BEIJING_CENTER;
        Coordinates end = TestDataBuilder.BEIJING_SHOPPING;

        double drivingDistance = worker.calculateRouteDistance(start, end, "driving");
        double walkingDistance = worker.calculateRouteDistance(start, end, "walking");
        double transitDistance = worker.calculateRouteDistance(start, end, "transit");

        assertTrue(drivingDistance > walkingDistance);
        assertTrue(transitDistance > walkingDistance);
        assertTrue(transitDistance > drivingDistance);
    }

    @Test
    @DisplayName("测试不同路径类型的时间预估 - 步行时间最长")
    void testRouteDuration_DifferentRouteTypes() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        double testDistance = 5000;

        int drivingDuration = worker.calculateRouteDuration(testDistance, "driving");
        int walkingDuration = worker.calculateRouteDuration(testDistance, "walking");
        int transitDuration = worker.calculateRouteDuration(testDistance, "transit");

        assertTrue(walkingDuration > drivingDuration);
        assertTrue(transitDuration > drivingDuration);
        assertTrue(walkingDuration > transitDuration);
    }

    @Test
    @DisplayName("测试路径点生成 - 包含起点和终点")
    void testGenerateRoutePath_ContainsStartAndEnd() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        Coordinates start = TestDataBuilder.BEIJING_CENTER;
        Coordinates end = TestDataBuilder.BEIJING_SHOPPING;

        List<Coordinates> path = worker.generateRoutePath(start, end, "driving");

        assertNotNull(path);
        assertTrue(path.size() >= 2);
        assertEquals(start, path.get(0));
        assertEquals(end, path.get(path.size() - 1));
    }

    @Test
    @DisplayName("测试复杂多节点路径计算")
    void testComplexRoute_MultiWaypoint() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        List<Coordinates> waypoints = TestDataBuilder.buildComplexWaypoints();

        Route result = worker.calculateComplexRoute(waypoints, "driving");

        assertNotNull(result);
        assertEquals(waypoints.get(0), result.getStartLocation());
        assertEquals(waypoints.get(waypoints.size() - 1), result.getEndLocation());
        assertTrue(result.getRouteDistance() > 0);
        assertTrue(result.getRouteDuration() > 0);
        assertNotNull(result.getRoutePath());
        assertTrue(result.getRoutePath().size() >= waypoints.size());
    }

    @Test
    @DisplayName("测试复杂路径 - 节点不足时抛异常")
    void testComplexRoute_InsufficientWaypoints() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        List<Coordinates> singleWaypoint = Arrays.asList(TestDataBuilder.BEIJING_CENTER);

        assertThrows(RuntimeException.class, () ->
                worker.calculateComplexRoute(singleWaypoint, "driving"));
    }

    @Test
    @DisplayName("测试异步路径规划 - 立即返回响应不阻塞")
    void testPlanRouteAsync_ImmediateResponse() throws InterruptedException {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskQueue.submitTask(any(), anyString())).thenReturn(
                AsyncRouteTaskQueue.RouteTask.builder()
                        .taskId("task_123")
                        .routeId("route_async_001")
                        .status(AsyncRouteTaskQueue.TaskStatus.PENDING)
                        .build()
        );

        CountDownLatch latch = new CountDownLatch(1);
        final long[] executionTime = new long[1];

        Thread testThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            RoutePlanResponse response = routeService.planRouteAsync(drivingRequest);
            long endTime = System.currentTimeMillis();
            executionTime[0] = endTime - startTime;

            assertNotNull(response);
            assertNotNull(response.getRouteId());
            latch.countDown();
        });

        testThread.start();
        boolean completed = latch.await(1, TimeUnit.SECONDS);

        assertTrue(completed, "异步请求应在1秒内返回");
        assertTrue(executionTime[0] < 1000, "异步请求执行时间应小于1秒，实际: " + executionTime[0] + "ms");
    }

    @Test
    @DisplayName("测试异步任务队列 - 任务提交和获取")
    void testAsyncTaskQueue_SubmitAndPoll() {
        AsyncRouteTaskQueue queue = new AsyncRouteTaskQueue();

        assertEquals(0, queue.getQueuedCount());
        assertTrue(queue.isEmpty());

        AsyncRouteTaskQueue.RouteTask task = queue.submitTask(drivingRequest, "route_001");

        assertNotNull(task);
        assertEquals("route_001", task.getRouteId());
        assertEquals(AsyncRouteTaskQueue.TaskStatus.PENDING, task.getStatus());
        assertEquals(1, queue.getQueuedCount());
        assertFalse(queue.isEmpty());

        AsyncRouteTaskQueue.RouteTask polledTask = queue.pollTask();

        assertNotNull(polledTask);
        assertEquals("route_001", polledTask.getRouteId());
        assertEquals(AsyncRouteTaskQueue.TaskStatus.PROCESSING, polledTask.getStatus());
        assertEquals(0, queue.getQueuedCount());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("测试异步任务队列 - 多任务处理顺序")
    void testAsyncTaskQueue_MultipleTasksOrder() {
        AsyncRouteTaskQueue queue = new AsyncRouteTaskQueue();

        queue.submitTask(drivingRequest, "route_1");
        queue.submitTask(walkingRequest, "route_2");
        queue.submitTask(transitRequest, "route_3");

        assertEquals(3, queue.getQueuedCount());

        assertEquals("route_1", queue.pollTask().getRouteId());
        assertEquals("route_2", queue.pollTask().getRouteId());
        assertEquals("route_3", queue.pollTask().getRouteId());

        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("测试坐标有效性校验 - 有效坐标")
    void testValidCoordinates() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutePlanRequest validRequest = RoutePlanRequest.builder()
                .startLocation(new Coordinates(39.9042, 116.4074))
                .endLocation(new Coordinates(40.0, 117.0))
                .routeType("driving")
                .build();

        RoutePlanResponse response = routeService.planRouteSync(validRequest);

        assertNotNull(response);
        assertTrue(response.getRouteDistance() > 0);
    }

    @Test
    @DisplayName("测试坐标有效性校验 - 无效坐标抛异常")
    void testInvalidCoordinates_ThrowsException() {
        RoutePlanRequest invalidLatRequest = RoutePlanRequest.builder()
                .startLocation(new Coordinates(100.0, 116.4074))
                .endLocation(new Coordinates(40.0, 117.0))
                .routeType("driving")
                .build();

        assertThrows(RuntimeException.class, () ->
                routeService.planRouteSync(invalidLatRequest));

        RoutePlanRequest invalidLngRequest = RoutePlanRequest.builder()
                .startLocation(new Coordinates(39.9042, 200.0))
                .endLocation(new Coordinates(40.0, 117.0))
                .routeType("driving")
                .build();

        assertThrows(RuntimeException.class, () ->
                routeService.planRouteSync(invalidLngRequest));
    }

    @Test
    @DisplayName("测试路径距离计算准确性")
    void testRouteDistanceAccuracy() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        Coordinates start = new Coordinates(39.9042, 116.4074);
        Coordinates end = new Coordinates(39.9043, 116.4075);

        double directDistance = worker.calculateRouteDistance(start, end, "direct");
        double drivingDistance = worker.calculateRouteDistance(start, end, "driving");
        double walkingDistance = worker.calculateRouteDistance(start, end, "walking");

        assertEquals(directDistance, walkingDistance, directDistance * 0.2);
        assertTrue(drivingDistance > directDistance);
    }

    @Test
    @DisplayName("测试路径时间预估准确性")
    void testRouteDurationAccuracy() {
        RouteTaskWorker worker = new RouteTaskWorker(routeRepository, analysisService, taskQueue);

        double distance = 5000;

        int drivingDuration = worker.calculateRouteDuration(distance, "driving");
        int walkingDuration = worker.calculateRouteDuration(distance, "walking");

        int expectedDrivingSeconds = (int) Math.round(distance / 16.67);
        int expectedWalkingSeconds = (int) Math.round(distance / 1.4);

        assertEquals(expectedDrivingSeconds, drivingDuration, 1);
        assertEquals(expectedWalkingSeconds, walkingDuration, 1);
    }

    @Test
    @DisplayName("测试获取路径信息 - 存在路径")
    void testGetRouteById_Exists() {
        Route expectedRoute = TestDataBuilder.buildRoute(
                "route_test_001",
                TestDataBuilder.BEIJING_CENTER,
                TestDataBuilder.BEIJING_SHOPPING,
                "driving",
                5000,
                300
        );

        when(routeRepository.findById("route_test_001")).thenReturn(Optional.of(expectedRoute));

        Route result = routeService.getRouteById("route_test_001");

        assertNotNull(result);
        assertEquals("route_test_001", result.getRouteId());
        assertEquals(5000, result.getRouteDistance());
        assertEquals(300, result.getRouteDuration());
    }

    @Test
    @DisplayName("测试获取路径信息 - 不存在路径返回null")
    void testGetRouteById_NotExists() {
        when(routeRepository.findById("non_existent")).thenReturn(Optional.empty());

        Route result = routeService.getRouteById("non_existent");

        assertNull(result);
    }

    @Test
    @DisplayName("测试路径规划 - 默认使用驾车类型")
    void testPlanRoute_DefaultRouteType() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoutePlanRequest noTypeRequest = RoutePlanRequest.builder()
                .startLocation(TestDataBuilder.BEIJING_CENTER)
                .endLocation(TestDataBuilder.BEIJING_SHOPPING)
                .build();

        RoutePlanResponse response = routeService.planRouteSync(noTypeRequest);

        assertNotNull(response);
        assertEquals("driving", response.getRouteType());
    }

    @Test
    @DisplayName("测试异步任务状态转换")
    void testAsyncTask_StatusTransition() {
        AsyncRouteTaskQueue queue = new AsyncRouteTaskQueue();

        AsyncRouteTaskQueue.RouteTask task = queue.submitTask(drivingRequest, "route_status_test");
        assertEquals(AsyncRouteTaskQueue.TaskStatus.PENDING, task.getStatus());

        AsyncRouteTaskQueue.RouteTask polledTask = queue.pollTask();
        assertEquals(AsyncRouteTaskQueue.TaskStatus.PROCESSING, polledTask.getStatus());

        polledTask.setStatus(AsyncRouteTaskQueue.TaskStatus.COMPLETED);
        assertEquals(AsyncRouteTaskQueue.TaskStatus.COMPLETED, polledTask.getStatus());
    }
}
