package com.loganalytics.common.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ServiceMetadata {
    private String serviceName;
    private String teamName;
    private String techLead;
    private String onCallEmail;
    private String slackChannel;
    private String pagerDutyServiceId;
    private String environment;
    private String version;
    private List<String> logPatterns;
    private Map<String, String> labels;
    private Instant lastUpdated;

    public ServiceMetadata() {}

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }

    public String getTechLead() { return techLead; }
    public void setTechLead(String techLead) { this.techLead = techLead; }

    public String getOnCallEmail() { return onCallEmail; }
    public void setOnCallEmail(String onCallEmail) { this.onCallEmail = onCallEmail; }

    public String getSlackChannel() { return slackChannel; }
    public void setSlackChannel(String slackChannel) { this.slackChannel = slackChannel; }

    public String getPagerDutyServiceId() { return pagerDutyServiceId; }
    public void setPagerDutyServiceId(String pagerDutyServiceId) { this.pagerDutyServiceId = pagerDutyServiceId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public List<String> getLogPatterns() { return logPatterns; }
    public void setLogPatterns(List<String> logPatterns) { this.logPatterns = logPatterns; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
}
