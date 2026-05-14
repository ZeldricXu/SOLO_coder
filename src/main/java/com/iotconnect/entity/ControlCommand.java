package com.iotconnect.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "control_commands")
public class ControlCommand {

    @Id
    @Column(name = "command_id", length = 64)
    private String commandId;

    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    @Column(name = "command_type", length = 64, nullable = false)
    private String commandType;

    @ElementCollection
    @CollectionTable(name = "command_params", joinColumns = @JoinColumn(name = "command_id"))
    @MapKeyColumn(name = "param_key")
    @Column(name = "param_value")
    private Map<String, String> commandParams = new HashMap<>();

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "execution_result", length = 512)
    private String executionResult;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "issued_by", length = 128)
    private String issuedBy;

    public ControlCommand() {
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public Map<String, String> getCommandParams() {
        return commandParams;
    }

    public void setCommandParams(Map<String, String> commandParams) {
        this.commandParams = commandParams;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }
}
