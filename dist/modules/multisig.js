"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.multiSigCoordinator = exports.MultiSigWalletCoordinator = void 0;
const ethers_1 = require("ethers");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class MultiSigWalletCoordinator {
    proposals;
    wallets;
    configHistory;
    logger;
    constructor() {
        this.proposals = new Map();
        this.wallets = new Map();
        this.configHistory = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'MultiSigWalletCoordinator' });
    }
    createWallet(walletId, signers, requiredSignatures) {
        this.logger.info('Creating multi-sig wallet', { walletId, signerCount: signers.length, requiredSignatures });
        this.validateSignerConfig(signers, requiredSignatures);
        const normalizedSigners = signers.map(utils_1.normalizeAddress);
        const uniqueSigners = [...new Set(normalizedSigners)];
        if (uniqueSigners.length !== normalizedSigners.length) {
            throw new Error('Duplicate signers are not allowed');
        }
        const config = {
            walletId,
            signers: uniqueSigners,
            requiredSignatures,
            nonce: 0,
            version: 1,
            updatedAt: (0, utils_1.now)(),
        };
        this.wallets.set(walletId, config);
        this.configHistory.set(walletId, []);
        this.logger.info('Multi-sig wallet created', { walletId, version: config.version });
        return config;
    }
    updateWalletConfig(params, changedBy, reason) {
        const { walletId, signers, requiredSignatures } = params;
        this.logger.info('Updating wallet config', { walletId, changedBy });
        const currentConfig = this.wallets.get(walletId);
        if (!currentConfig) {
            throw new Error(`Wallet not found: ${walletId}`);
        }
        const newSigners = signers ?? currentConfig.signers;
        const newRequiredSignatures = requiredSignatures ?? currentConfig.requiredSignatures;
        if (signers !== undefined) {
            this.validateSignerConfig(signers, newRequiredSignatures);
        }
        const normalizedSigners = signers?.map(utils_1.normalizeAddress);
        const uniqueSigners = normalizedSigners ? [...new Set(normalizedSigners)] : currentConfig.signers;
        const hasChanges = (signers !== undefined && JSON.stringify(uniqueSigners) !== JSON.stringify(currentConfig.signers)) ||
            (requiredSignatures !== undefined && requiredSignatures !== currentConfig.requiredSignatures);
        if (!hasChanges) {
            this.logger.info('No config changes detected', { walletId });
            return currentConfig;
        }
        const changeRecord = {
            id: (0, utils_1.generateId)('config_change'),
            walletId,
            oldConfig: {
                signers: currentConfig.signers,
                requiredSignatures: currentConfig.requiredSignatures,
            },
            newConfig: {
                signers: uniqueSigners,
                requiredSignatures: newRequiredSignatures,
            },
            changedAt: (0, utils_1.now)(),
            changedBy,
            reason,
        };
        this.configHistory.get(walletId)?.push(changeRecord);
        const updatedConfig = {
            ...currentConfig,
            signers: uniqueSigners,
            requiredSignatures: newRequiredSignatures,
            version: currentConfig.version + 1,
            updatedAt: (0, utils_1.now)(),
        };
        this.wallets.set(walletId, updatedConfig);
        this.updatePendingProposalsForConfigChange(walletId, updatedConfig);
        events_1.eventBus.emit(events_1.EVENTS.WALLET_CONFIG_UPDATED, {
            walletId,
            oldVersion: currentConfig.version,
            newVersion: updatedConfig.version,
            changes: changeRecord,
        });
        this.logger.info('Wallet config updated', {
            walletId,
            oldVersion: currentConfig.version,
            newVersion: updatedConfig.version,
        });
        return updatedConfig;
    }
    getWalletConfig(walletId) {
        return this.wallets.get(walletId);
    }
    getConfigHistory(walletId) {
        return this.configHistory.get(walletId) || [];
    }
    validateSignerConfig(signers, requiredSignatures) {
        if (requiredSignatures > signers.length) {
            throw new Error('Required signatures cannot exceed number of signers');
        }
        if (requiredSignatures <= 0) {
            throw new Error('Required signatures must be greater than 0');
        }
        signers.forEach((signer) => {
            if (!(0, ethers_1.isAddress)(signer)) {
                throw new Error(`Invalid signer address: ${signer}`);
            }
        });
    }
    updatePendingProposalsForConfigChange(walletId, newConfig) {
        const pendingProposals = Array.from(this.proposals.values()).filter((p) => p.walletId === walletId && p.status === 'pending');
        for (const proposal of pendingProposals) {
            const signedSigners = proposal.currentSignatures.map((s) => s.split(':')[0]);
            const stillAuthorizedSigners = signedSigners.filter((s) => newConfig.signers.includes(s));
            proposal.requiredSignatures = newConfig.requiredSignatures;
            proposal.signers = newConfig.signers;
            proposal.currentSignatures = proposal.currentSignatures.filter((s) => {
                const signer = s.split(':')[0];
                return newConfig.signers.includes(signer);
            });
            if (proposal.currentSignatures.length >= proposal.requiredSignatures) {
                proposal.status = 'approved';
                this.logger.info('Proposal auto-approved due to config change', {
                    proposalId: proposal.id,
                });
            }
            else if (proposal.status === 'approved') {
                proposal.status = 'pending';
                this.logger.info('Proposal reverted to pending due to config change', {
                    proposalId: proposal.id,
                });
            }
        }
    }
    createProposal(params) {
        const { walletId, destination, value, data = '0x', description } = params;
        this.logger.info('Creating proposal', { walletId, destination, value });
        const wallet = this.wallets.get(walletId);
        if (!wallet) {
            throw new Error(`Wallet not found: ${walletId}`);
        }
        if (!(0, ethers_1.isAddress)(destination)) {
            throw new Error(`Invalid destination address: ${destination}`);
        }
        const proposal = {
            id: (0, utils_1.generateId)('proposal'),
            walletId,
            transactionData: data,
            destination: (0, utils_1.normalizeAddress)(destination),
            value,
            nonce: wallet.nonce,
            requiredSignatures: wallet.requiredSignatures,
            currentSignatures: [],
            signers: wallet.signers,
            status: 'pending',
            createdAt: (0, utils_1.now)(),
            description,
        };
        this.proposals.set(proposal.id, proposal);
        events_1.eventBus.emit(events_1.EVENTS.PROPOSAL_CREATED, proposal);
        this.logger.info('Proposal created', { proposalId: proposal.id });
        return proposal;
    }
    addSignature(proposalId, signature, signer) {
        this.logger.info('Adding signature', { proposalId, signer });
        const proposal = this.proposals.get(proposalId);
        if (!proposal) {
            throw new Error(`Proposal not found: ${proposalId}`);
        }
        if (proposal.status !== 'pending') {
            throw new Error(`Proposal is not pending: ${proposal.status}`);
        }
        const normalizedSigner = (0, utils_1.normalizeAddress)(signer);
        if (!proposal.signers.includes(normalizedSigner)) {
            throw new Error(`Signer ${signer} is not authorized for this proposal`);
        }
        const prefix = normalizedSigner + ':';
        if (proposal.currentSignatures.some((s) => s.startsWith(prefix))) {
            throw new Error(`Signer ${signer} has already signed this proposal`);
        }
        const isSignatureValid = this.verifySignature(proposal, signature, normalizedSigner);
        if (!isSignatureValid) {
            throw new Error('Invalid signature');
        }
        proposal.currentSignatures.push(normalizedSigner + ':' + signature);
        if (proposal.currentSignatures.length >= proposal.requiredSignatures) {
            proposal.status = 'approved';
            this.logger.info('Proposal approved', { proposalId, signatureCount: proposal.currentSignatures.length });
        }
        events_1.eventBus.emit(events_1.EVENTS.PROPOSAL_SIGNED, { proposalId, signer: normalizedSigner });
        return proposal;
    }
    verifySignature(proposal, signature, signer) {
        try {
            const message = this.getProposalMessage(proposal);
            const recoveredAddress = this.recoverSigner(message, signature);
            return (0, utils_1.normalizeAddress)(recoveredAddress) === (0, utils_1.normalizeAddress)(signer);
        }
        catch (error) {
            this.logger.error('Signature verification failed', error, { proposalId: proposal.id });
            return false;
        }
    }
    getProposalMessage(proposal) {
        return JSON.stringify({
            walletId: proposal.walletId,
            destination: proposal.destination,
            value: proposal.value,
            data: proposal.transactionData,
            nonce: proposal.nonce,
        });
    }
    recoverSigner(message, signature) {
        try {
            const messageHash = (0, ethers_1.hashMessage)(message);
            return (0, ethers_1.recoverAddress)((0, ethers_1.getBytes)(messageHash), signature);
        }
        catch (error) {
            this.logger.error('Failed to recover signer', error);
            throw new Error('Invalid signature format');
        }
    }
    async executeProposal(proposalId) {
        this.logger.info('Executing proposal', { proposalId });
        const proposal = this.proposals.get(proposalId);
        if (!proposal) {
            throw new Error(`Proposal not found: ${proposalId}`);
        }
        if (proposal.status !== 'approved') {
            throw new Error(`Proposal is not approved: ${proposal.status}`);
        }
        const result = await (0, utils_1.withRetry)(async () => {
            const transactionHash = (0, utils_1.generateId)('tx');
            proposal.status = 'executed';
            proposal.executedAt = (0, utils_1.now)();
            const wallet = this.wallets.get(proposal.walletId);
            if (wallet) {
                wallet.nonce++;
            }
            return { proposal, transactionHash };
        }, {
            retries: 3,
            onRetry: (error, attempt) => {
                this.logger.warn('Retrying proposal execution', { proposalId, attempt, error: (0, utils_1.getErrorMessage)(error) });
            },
        });
        events_1.eventBus.emit(events_1.EVENTS.PROPOSAL_EXECUTED, result);
        this.logger.info('Proposal executed', { proposalId, transactionHash: result.transactionHash });
        return result;
    }
    rejectProposal(proposalId) {
        this.logger.info('Rejecting proposal', { proposalId });
        const proposal = this.proposals.get(proposalId);
        if (!proposal) {
            throw new Error(`Proposal not found: ${proposalId}`);
        }
        if (proposal.status !== 'pending') {
            throw new Error(`Proposal cannot be rejected: ${proposal.status}`);
        }
        proposal.status = 'rejected';
        this.logger.info('Proposal rejected', { proposalId });
        return proposal;
    }
    getProposal(proposalId) {
        return this.proposals.get(proposalId);
    }
    listProposals(walletId, status) {
        let proposals = Array.from(this.proposals.values());
        if (walletId) {
            proposals = proposals.filter((p) => p.walletId === walletId);
        }
        if (status) {
            proposals = proposals.filter((p) => p.status === status);
        }
        return proposals.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    getWallet(walletId) {
        return this.wallets.get(walletId);
    }
    listWallets() {
        return Array.from(this.wallets.keys());
    }
    getProposalSigners(proposalId) {
        const proposal = this.proposals.get(proposalId);
        if (!proposal) {
            throw new Error(`Proposal not found: ${proposalId}`);
        }
        const signed = proposal.currentSignatures.map((s) => s.split(':')[0]);
        const pending = proposal.signers.filter((s) => !signed.includes(s));
        return { signed, pending };
    }
}
exports.MultiSigWalletCoordinator = MultiSigWalletCoordinator;
exports.multiSigCoordinator = new MultiSigWalletCoordinator();
//# sourceMappingURL=multisig.js.map