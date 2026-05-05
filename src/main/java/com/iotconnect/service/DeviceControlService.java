package com.iotconnect.service;

import com.iotconnect.dto.ControlCommandRequest;
import com.iotconnect.dto.ControlCommandResponse;
import com.iotconnect.entity.ControlCommand;
import com.iotconnect.enums.CommandStatus;
import com.iotconnect.enums.ConnectionStatus;
import com.iotconnect.repository.ControlCommandRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceControlService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceControlService.class);

    private final ControlCommandRepository commandRepository;
    private final ConnectionService connectionService;
    private final DeviceService deviceService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DeviceControlService(ControlCommandRepository commandRepository,
                                 ConnectionService connectionService,
                                 DeviceService deviceService,
                                 KafkaTemplate<String, String> kafkaTemplate) {
        this.commandRepository = commandRepository;
        this.connectionService = connectionService;
        this.deviceService = deviceService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public ControlCommandResponse issueCommand(ControlCommandRequest request) {
        String deviceId = request.getDeviceId();

        if (!deviceService.findByDeviceId(deviceId).isPresent()) {
            throw new RuntimeException("Device not found: " + deviceId);
        }

        if (!connectionService.isDeviceOnline(deviceId)) {
            throw new RuntimeException("Device is offline, cannot issue command: " + deviceId);
        }

        ControlCommand command = new ControlCommand();
        command.setCommandId(generateCommandId());
        command.setDeviceId(deviceId);
        command.setCommandType(request.getCommandType());
        command.setCommandParams(request.getCommandParams());
        command.setStatus(CommandStatus.PENDING.getValue());
        command.setIssuedAt(LocalDateTime.now());
        command.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30);
        command.setIssuedBy("system");

        ControlCommand savedCommand = commandRepository.save(command);

        sendCommandToDevice(savedCommand);

        logger.info("Control command issued: commandId={}, deviceId={}, commandType={}",
                savedCommand.getCommandId(), savedCommand.getDeviceId(), savedCommand.getCommandType());

        return new ControlCommandResponse(savedCommand.getCommandId(), savedCommand.getStatus());
    }

    private void sendCommandToDevice(ControlCommand command) {
        String topic = "iot/device/" + command.getDeviceId() + "/control";
        
        String payload = buildCommandPayload(command);

        try {
            publishToKafka(topic, payload, command);
        } catch (Exception e) {
            logger.error("Failed to send command to device: commandId={}, error={}",
                    command.getCommandId(), e.getMessage());
        }
    }

    private String buildCommandPayload(ControlCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"command_id\":\"").append(command.getCommandId()).append("\",");
        sb.append("\"command_type\":\"").append(command.getCommandType()).append("\",");
        sb.append("\"params\":{");
        
        if (command.getCommandParams() != null && !command.getCommandParams().isEmpty()) {
            boolean first = true;
            for (java.util.Map.Entry<String, String> entry : command.getCommandParams().entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                first = false;
            }
        }
        
        sb.append("},");
        sb.append("\"issued_at\":\"").append(command.getIssuedAt().toString()).append("\"");
        sb.append("}");
        
        return sb.toString();
    }

    private void publishToKafka(String topic, String payload, ControlCommand command) {
        kafkaTemplate.send("iot-control-commands", command.getDeviceId(), payload);
        logger.debug("Command published to Kafka: commandId={}", command.getCommandId());
    }

    @Transactional
    public void handleCommandResponse(String deviceId, String commandId, String status, String result, String errorMessage) {
        Optional<ControlCommand> commandOpt = commandRepository.findById(commandId);
        
        if (commandOpt.isEmpty()) {
            logger.warn("Command response received for unknown command: commandId={}", commandId);
            return;
        }

        ControlCommand command = commandOpt.get();
        
        if (!deviceId.equals(command.getDeviceId())) {
            logger.warn("Command response device mismatch: expected={}, received={}", 
                    command.getDeviceId(), deviceId);
            return;
        }

        CommandStatus newStatus = CommandStatus.fromValue(status);
        command.setStatus(newStatus.getValue());
        command.setExecutedAt(LocalDateTime.now());
        command.setExecutionResult(result);
        command.setErrorMessage(errorMessage);

        commandRepository.save(command);

        logger.info("Command response processed: commandId={}, status={}", commandId, status);
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void checkTimeoutCommands() {
        LocalDateTime now = LocalDateTime.now();
        List<ControlCommand> pendingCommands = commandRepository.findByStatus(CommandStatus.PENDING.getValue());

        for (ControlCommand command : pendingCommands) {
            if (command.getTimeoutSeconds() == null) {
                command.setTimeoutSeconds(30);
            }

            LocalDateTime timeoutTime = command.getIssuedAt().plusSeconds(command.getTimeoutSeconds());
            
            if (now.isAfter(timeoutTime)) {
                command.setStatus(CommandStatus.TIMEOUT.getValue());
                command.setExecutedAt(now);
                command.setErrorMessage("Command execution timeout");
                
                commandRepository.save(command);
                logger.warn("Command timeout: commandId={}, deviceId={}", 
                        command.getCommandId(), command.getDeviceId());
            }
        }
    }

    public Optional<ControlCommand> getCommandStatus(String commandId) {
        return commandRepository.findById(commandId);
    }

    public List<ControlCommand> getCommandsByDevice(String deviceId) {
        return commandRepository.findCommandHistoryByDeviceId(deviceId);
    }

    public List<ControlCommand> getPendingCommands() {
        return commandRepository.findByStatus(CommandStatus.PENDING.getValue());
    }

    public long getPendingCommandCount() {
        return commandRepository.countPendingCommands();
    }

    private String generateCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
