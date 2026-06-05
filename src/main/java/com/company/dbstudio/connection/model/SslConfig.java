package com.company.dbstudio.connection.model;

public class SslConfig {

    private boolean enabled = false;
    private boolean verifyServerCertificate = true;
    private String trustStorePath;
    private String trustStorePassword;
    private String keyStorePath;
    private String keyStorePassword;
    private String clientCertificatePath;
    private String clientKeyPath;
    private String caCertificatePath;
    private String sslProtocol = "TLSv1.2";
    private String[] enabledCipherSuites;
    private boolean requireSsl = false;

    public SslConfig() {
    }

    public SslConfig copy() {
        SslConfig copy = new SslConfig();
        copy.enabled = this.enabled;
        copy.verifyServerCertificate = this.verifyServerCertificate;
        copy.trustStorePath = this.trustStorePath;
        copy.trustStorePassword = this.trustStorePassword;
        copy.keyStorePath = this.keyStorePath;
        copy.keyStorePassword = this.keyStorePassword;
        copy.clientCertificatePath = this.clientCertificatePath;
        copy.clientKeyPath = this.clientKeyPath;
        copy.caCertificatePath = this.caCertificatePath;
        copy.sslProtocol = this.sslProtocol;
        copy.enabledCipherSuites = this.enabledCipherSuites != null ? this.enabledCipherSuites.clone() : null;
        copy.requireSsl = this.requireSsl;
        return copy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVerifyServerCertificate() {
        return verifyServerCertificate;
    }

    public void setVerifyServerCertificate(boolean verifyServerCertificate) {
        this.verifyServerCertificate = verifyServerCertificate;
    }

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getKeyStorePath() {
        return keyStorePath;
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getClientCertificatePath() {
        return clientCertificatePath;
    }

    public void setClientCertificatePath(String clientCertificatePath) {
        this.clientCertificatePath = clientCertificatePath;
    }

    public String getClientKeyPath() {
        return clientKeyPath;
    }

    public void setClientKeyPath(String clientKeyPath) {
        this.clientKeyPath = clientKeyPath;
    }

    public String getCaCertificatePath() {
        return caCertificatePath;
    }

    public void setCaCertificatePath(String caCertificatePath) {
        this.caCertificatePath = caCertificatePath;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String[] getEnabledCipherSuites() {
        return enabledCipherSuites;
    }

    public void setEnabledCipherSuites(String[] enabledCipherSuites) {
        this.enabledCipherSuites = enabledCipherSuites;
    }

    public boolean isRequireSsl() {
        return requireSsl;
    }

    public void setRequireSsl(boolean requireSsl) {
        this.requireSsl = requireSsl;
    }

    public boolean hasTrustStore() {
        return trustStorePath != null && !trustStorePath.isEmpty();
    }

    public boolean hasKeyStore() {
        return keyStorePath != null && !keyStorePath.isEmpty();
    }

    public boolean hasCaCertificate() {
        return caCertificatePath != null && !caCertificatePath.isEmpty();
    }

    public boolean hasClientCertificate() {
        return clientCertificatePath != null && !clientCertificatePath.isEmpty()
                && clientKeyPath != null && !clientKeyPath.isEmpty();
    }
}
