package com.exam.mq;

import com.exam.common.Constants;
import com.exam.service.GradingService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class GradingMessageListener {

    private final GradingService gradingService;

    @RabbitListener(queues = Constants.MQ_QUEUE_GRADING)
    public void handleGradingMessage(Long examRecordId, Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            log.info("收到阅卷消息: examRecordId={}", examRecordId);

            gradingService.autoGradeObjectiveQuestions(examRecordId);

            gradingService.autoGradeProgrammingQuestions(examRecordId);

            gradingService.completeGrading(examRecordId);

            channel.basicAck(tag, false);

            log.info("阅卷完成: examRecordId={}", examRecordId);
        } catch (Exception e) {
            log.error("阅卷处理失败: examRecordId={}, error={}", examRecordId, e.getMessage(), e);
            channel.basicNack(tag, false, true);
        }
    }
}
