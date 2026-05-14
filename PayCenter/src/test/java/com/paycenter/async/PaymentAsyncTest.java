package com.paycenter.async;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.*;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.PaymentChannelService;
import com.paycenter.service.TransactionService;
import com.paycenter.service.TransactionStatusService;
import com.paycenter.testdata.TestDataBuilder;
import com.paycenter.util.IdGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("支付异步化机制测试")
class PaymentAsyncTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentChannelService paymentChannelService;

    @Mock
    private TransactionStatusService transactionStatusService;

    @InjectMocks
    private MockAsyncPaymentService asyncPaymentService;

    private PaymentChannel alipayChannel;
    private PaymentRequest validPaymentRequest;

    @BeforeEach
    void setUp() {
        alipayChannel = TestDataBuilder.buildAlipayChannel();
        validPaymentRequest = new PaymentRequest();
        validPaymentRequest.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);
        validPaymentRequest.setOrderNo(IdGenerator.generateOrderNo());
        validPaymentRequest.setAmount(TestDataBuilder.TEST_AMOUNT);
        validPaymentRequest.setChannel("ALIPAY");

        asyncPaymentService = new MockAsyncPaymentService(
                transactionRepository, paymentChannelService, transactionStatusService);
    }

    @Nested
    @DisplayName("异步支付基本测试")
    class BasicAsyncPaymentTests {

        @Test
        @DisplayName("支付请求提交后立即返回响应")
        void testPaymentRequestReturnsImmediately() {
            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            long startTime = System.currentTimeMillis();
            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            assertNotNull(response);
            assertNotNull(response.getTransactionId());
            assertEquals("pending", response.getStatus());
            assertTrue(duration < 100, "支付请求应该在100ms内返回，实际耗时: " + duration + "ms");
        }

        @Test
        @DisplayName("后台Worker执行渠道调用")
        void testWorkerExecutesChannelCall() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean workerExecuted = new AtomicBoolean(false);

            asyncPaymentService.setChannelCallCallback(() -> {
                workerExecuted.set(true);
                latch.countDown();
            });

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);
            assertNotNull(response);

            boolean workerCompleted = latch.await(2, TimeUnit.SECONDS);
            assertTrue(workerCompleted, "Worker应该在2秒内执行渠道调用");
            assertTrue(workerExecuted.get(), "Worker应该已经执行渠道调用");
        }

        @Test
        @DisplayName("交易状态在异步处理中保持一致")
        void testTransactionStateConsistencyInAsync() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            Transaction[] capturedTransaction = new Transaction[1];

            asyncPaymentService.setChannelCallCallback(() -> {
                capturedTransaction[0] = asyncPaymentService.getLastTransaction();
                latch.countDown();
            });

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.findById(anyString()))
                    .thenReturn(Optional.of(TestDataBuilder.buildPendingTransaction()));

            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);
            latch.await(2, TimeUnit.SECONDS);

            Optional<Transaction> transactionOpt = asyncPaymentService.getTransactionById(response.getTransactionId());
            assertTrue(transactionOpt.isPresent());
            assertEquals(TransactionStatus.PENDING, transactionOpt.get().getStatus());
        }
    }

    @Nested
    @DisplayName("异步延迟和并发测试")
    class DelayedAndConcurrentTests {

        @Test
        @DisplayName("渠道响应延迟不阻塞接口")
        void testChannelDelayDoesNotBlockInterface() throws InterruptedException {
            int delayMs = 500;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicLong workerStartTime = new AtomicLong(0);
            AtomicLong workerEndTime = new AtomicLong(0);

            asyncPaymentService.setChannelCallCallback(() -> {
                workerStartTime.set(System.currentTimeMillis());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                workerEndTime.set(System.currentTimeMillis());
                latch.countDown();
            });

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            long requestStartTime = System.currentTimeMillis();
            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);
            long requestEndTime = System.currentTimeMillis();
            long requestDuration = requestEndTime - requestStartTime;

            assertTrue(requestDuration < 200, "接口应该在延迟期间立即返回，实际耗时: " + requestDuration + "ms");
            assertNotNull(response);

            boolean completed = latch.await(2, TimeUnit.SECONDS);
            assertTrue(completed, "Worker应该完成处理");

            long workerDuration = workerEndTime.get() - workerStartTime.get();
            assertTrue(workerDuration >= delayMs, "Worker应该经历了延迟: " + workerDuration + "ms");
        }

        @Test
        @DisplayName("并发支付请求处理")
        void testConcurrentPaymentRequests() throws InterruptedException, ExecutionException {
            int requestCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(requestCount);
            CountDownLatch latch = new CountDownLatch(requestCount);

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
                Thread.sleep(10);
                return inv.getArgument(0);
            });

            asyncPaymentService.setChannelCallCallback(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            });

            List<Callable<PaymentResponse>> tasks = new CopyOnWriteArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                final int index = i;
                tasks.add(() -> {
                    PaymentRequest request = new PaymentRequest();
                    request.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);
                    request.setOrderNo("ORDER_" + index + "_" + System.currentTimeMillis());
                    request.setAmount(new BigDecimal("100.00").add(new BigDecimal(index)));
                    request.setChannel("ALIPAY");
                    return asyncPaymentService.createPaymentAsync(request);
                });
            }

            long startTime = System.currentTimeMillis();
            List<Future<PaymentResponse>> futures = executor.invokeAll(tasks);
            long endTime = System.currentTimeMillis();

            for (Future<PaymentResponse> future : futures) {
                PaymentResponse response = future.get();
                assertNotNull(response);
                assertNotNull(response.getTransactionId());
                assertEquals("pending", response.getStatus());
            }

            long totalDuration = endTime - startTime;
            assertTrue(totalDuration < 1000, "5个并发请求应该在1秒内完成，实际耗时: " + totalDuration + "ms");

            boolean allWorkersCompleted = latch.await(3, TimeUnit.SECONDS);
            assertTrue(allWorkersCompleted, "所有Worker应该完成处理");

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("异步状态追踪测试")
    class AsyncStateTrackingTests {

        @Test
        @DisplayName("异步处理过程中交易状态流转正确")
        void testAsyncTransactionStateFlow() throws InterruptedException {
            CountDownLatch processingLatch = new CountDownLatch(1);
            CountDownLatch completedLatch = new CountDownLatch(1);

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.findById(anyString()))
                    .thenReturn(Optional.of(TestDataBuilder.buildPendingTransaction()));

            asyncPaymentService.setChannelCallCallback(() -> {
                processingLatch.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completedLatch.countDown();
            });

            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);

            Optional<Transaction> initialTransaction = asyncPaymentService.getTransactionById(response.getTransactionId());
            assertTrue(initialTransaction.isPresent());
            assertEquals(TransactionStatus.PENDING, initialTransaction.get().getStatus());

            boolean reachedProcessing = processingLatch.await(1, TimeUnit.SECONDS);
            assertTrue(reachedProcessing, "应该到达处理阶段");

            boolean completed = completedLatch.await(3, TimeUnit.SECONDS);
            assertTrue(completed, "应该完成处理");

            verify(transactionStatusService, atLeastOnce()).logStatusChange(
                    eq(response.getTransactionId()), isNull(), eq(TransactionStatus.PENDING), anyString());
        }
    }

    @Nested
    @DisplayName("异步异常处理测试")
    class AsyncExceptionHandlingTests {

        @Test
        @DisplayName("渠道调用异常不影响请求响应")
        void testChannelExceptionDoesNotAffectResponse() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean exceptionOccurred = new AtomicBoolean(false);

            asyncPaymentService.setChannelCallCallback(() -> {
                exceptionOccurred.set(true);
                latch.countDown();
                throw new RuntimeException("渠道接口连接超时");
            });

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);

            assertNotNull(response);
            assertEquals("pending", response.getStatus());

            boolean completed = latch.await(2, TimeUnit.SECONDS);
            assertTrue(completed, "应该触发渠道调用回调");
            assertTrue(exceptionOccurred.get(), "应该发生异常");
        }

        @Test
        @DisplayName("异步处理异常后的交易状态")
        void testTransactionStatusAfterAsyncException() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);

            asyncPaymentService.setChannelCallCallback(() -> {
                latch.countDown();
                throw new RuntimeException("渠道内部错误");
            });

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.findById(anyString()))
                    .thenReturn(Optional.of(TestDataBuilder.buildPendingTransaction()));

            PaymentResponse response = asyncPaymentService.createPaymentAsync(validPaymentRequest);
            latch.await(2, TimeUnit.SECONDS);

            Optional<Transaction> transaction = asyncPaymentService.getTransactionById(response.getTransactionId());
            assertTrue(transaction.isPresent());
            assertTrue(transaction.get().getStatus() == TransactionStatus.PENDING ||
                       transaction.get().getStatus() == TransactionStatus.FAILED);
        }
    }

    static class MockAsyncPaymentService {
        private final TransactionRepository transactionRepository;
        private final PaymentChannelService paymentChannelService;
        private final TransactionStatusService transactionStatusService;
        private final ExecutorService executorService = Executors.newFixedThreadPool(4);
        private volatile Transaction lastTransaction;
        private Runnable channelCallCallback;

        public MockAsyncPaymentService(TransactionRepository transactionRepository,
                                       PaymentChannelService paymentChannelService,
                                       TransactionStatusService transactionStatusService) {
            this.transactionRepository = transactionRepository;
            this.paymentChannelService = paymentChannelService;
            this.transactionStatusService = transactionStatusService;
        }

        public void setChannelCallCallback(Runnable callback) {
            this.channelCallCallback = callback;
        }

        public Transaction getLastTransaction() {
            return lastTransaction;
        }

        public PaymentResponse createPaymentAsync(PaymentRequest request) {
            Optional<Transaction> existing = transactionRepository.findByOrderNo(request.getOrderNo());
            if (existing.isPresent()) {
                throw new RuntimeException("订单号已存在");
            }

            ChannelType channelType = ChannelType.valueOf(request.getChannel().toUpperCase());
            Optional<PaymentChannel> channel = paymentChannelService.getChannelByType(channelType);
            if (channel.isEmpty()) {
                throw new RuntimeException("支付渠道未配置");
            }

            String transactionId = IdGenerator.generateTransactionId();
            Transaction transaction = Transaction.builder()
                    .transactionId(transactionId)
                    .merchantId(request.getMerchantId())
                    .orderNo(request.getOrderNo())
                    .amount(request.getAmount())
                    .channelId(channel.get().getChannelId())
                    .transactionType(TransactionType.PAYMENT)
                    .status(TransactionStatus.PENDING)
                    .refundedAmount(BigDecimal.ZERO)
                    .notifyReceived(false)
                    .build();

            lastTransaction = transaction;
            transactionRepository.save(transaction);

            transactionStatusService.logStatusChange(
                    transactionId, null, TransactionStatus.PENDING, "创建支付订单");

            executorService.submit(() -> {
                try {
                    if (channelCallCallback != null) {
                        channelCallCallback.run();
                    }
                    Thread.sleep(50);
                } catch (Exception e) {
                    System.err.println("异步处理异常: " + e.getMessage());
                }
            });

            return PaymentResponse.builder()
                    .transactionId(transactionId)
                    .status("pending")
                    .build();
        }

        public Optional<Transaction> getTransactionById(String transactionId) {
            return transactionRepository.findById(transactionId);
        }
    }
}
