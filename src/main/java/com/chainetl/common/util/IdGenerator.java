package com.chainetl.common.util;

import java.util.UUID;

public class IdGenerator {

    private static final String ENTITY_PREFIX = "ent_";
    private static final String CONFIG_PREFIX = "cfg_";
    private static final String RUN_PREFIX = "run_";
    private static final String SNAPSHOT_PREFIX = "snap_";
    private static final String PROPOSAL_PREFIX = "prop_";
    private static final String SIGNATURE_PREFIX = "sig_";
    private static final String STORAGE_PREFIX = "stor_";
    private static final String BLOCK_PREFIX = "blk_";
    private static final String TX_PREFIX = "tx_";
    private static final String LISTENER_PREFIX = "lst_";
    private static final String LOG_PREFIX = "log_";
    private static final String PROOF_PREFIX = "proof_";
    private static final String ESTIMATE_PREFIX = "est_";
    private static final String NODE_PREFIX = "node_";

    public static String generateEntityId() {
        return ENTITY_PREFIX + shortUuid();
    }

    public static String generateConfigId() {
        return CONFIG_PREFIX + shortUuid();
    }

    public static String generateRunId() {
        return RUN_PREFIX + shortUuid();
    }

    public static String generateSnapshotId() {
        return SNAPSHOT_PREFIX + shortUuid();
    }

    public static String generateProposalId() {
        return PROPOSAL_PREFIX + shortUuid();
    }

    public static String generateSignatureId() {
        return SIGNATURE_PREFIX + shortUuid();
    }

    public static String generateStorageId() {
        return STORAGE_PREFIX + shortUuid();
    }

    public static String generateBlockId() {
        return BLOCK_PREFIX + shortUuid();
    }

    public static String generateTxId() {
        return TX_PREFIX + shortUuid();
    }

    public static String generateListenerId() {
        return LISTENER_PREFIX + shortUuid();
    }

    public static String generateLogId() {
        return LOG_PREFIX + shortUuid();
    }

    public static String generateProofId() {
        return PROOF_PREFIX + shortUuid();
    }

    public static String generateEstimateId() {
        return ESTIMATE_PREFIX + shortUuid();
    }

    public static String generateNodeId() {
        return NODE_PREFIX + shortUuid();
    }

    public static String generateBatchId() {
        return "batch_" + shortUuid();
    }

    public static String generateResourceId() {
        return "rsc_" + shortUuid();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
