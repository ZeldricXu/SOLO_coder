package com.apishield.audit.api;

public interface AuditFacade extends
        AuditLogCreator,
        AuditLogQueryService,
        AuditIntegrityVerifier,
        HashChainManager {
}
