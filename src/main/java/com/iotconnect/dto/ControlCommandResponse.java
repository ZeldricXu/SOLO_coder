package com.iotconnect.dto;

public class ControlCommandResponse {

    private String commandId;
    private String status;

    public ControlCommandResponse() {
    }

    public ControlCommandResponse(String commandId, String status) {
        this.commandId = commandId;
        this.status = status;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
