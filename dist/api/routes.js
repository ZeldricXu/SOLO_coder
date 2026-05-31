"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const validation_1 = require("../common/validation");
const validation_2 = require("../common/validation");
const utils_1 = require("../common/utils");
const logger_1 = require("../common/logger");
const multisig_1 = require("../modules/multisig");
const zkp_1 = require("../modules/zkp");
const events_1 = require("../modules/events");
const transaction_1 = require("../modules/transaction");
const crosschain_1 = require("../modules/crosschain");
const hdwallet_1 = require("../modules/hdwallet");
const storage_1 = require("../modules/storage");
const indexer_1 = require("../modules/indexer");
const chainadapter_1 = require("../modules/chainadapter");
const gas_1 = require("../modules/gas");
const router = express_1.default.Router();
const logger = new logger_1.LoggerContext({ module: 'API' });
function handleError(res, error) {
    if (error instanceof validation_1.ValidationError) {
        res.status(422).json({
            code: 422,
            message: error.message,
            data: { errors: error.details },
        });
        return;
    }
    if (error instanceof Error) {
        logger.error('API error', error);
        res.status(500).json({
            code: 500,
            message: error.message || 'Internal server error',
        });
        return;
    }
    logger.error('Unknown API error', error);
    res.status(500).json({
        code: 500,
        message: 'Internal server error',
    });
}
function successResponse(res, data, code = 200) {
    res.status(code).json({ code, data });
}
router.post('/resources', (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.createResourceSchema, req.body);
        const resource = {
            id: (0, utils_1.generateId)('rsc'),
            type: params.type,
            status: 'provisioning',
            attributes: params.config,
            created_at: (0, utils_1.now)(),
            updated_at: (0, utils_1.now)(),
        };
        successResponse(res, { id: resource.id, status: resource.status }, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/resources/:id/status', (req, res) => {
    try {
        const { id } = req.params;
        const run = {
            run_id: (0, utils_1.generateId)('run'),
            entity_id: id,
            phase: 'initializing',
            progress: 0.8,
            started_at: (0, utils_1.now)(),
            completed_at: null,
            error_detail: null,
        };
        successResponse(res, { id, status: 'running', progress: run.progress });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/resources/batch', (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.batchOperationSchema, req.body);
        const results = params.operations.map((op) => ({
            id: op.id,
            action: op.action,
            status: 'success',
        }));
        successResponse(res, { batch_id: (0, utils_1.generateId)('batch'), results });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/multisig/wallets', (req, res) => {
    try {
        const { walletId, signers, requiredSignatures } = req.body;
        const wallet = multisig_1.multiSigCoordinator.createWallet(walletId, signers, requiredSignatures);
        successResponse(res, wallet, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/multisig/wallets', (_req, res) => {
    try {
        const wallets = multisig_1.multiSigCoordinator.listWallets();
        successResponse(res, wallets);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/multisig/proposals', (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.multiSigProposalSchema, req.body);
        const proposal = multisig_1.multiSigCoordinator.createProposal(params);
        successResponse(res, proposal, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/multisig/proposals', (req, res) => {
    try {
        const { walletId, status } = req.query;
        const proposals = multisig_1.multiSigCoordinator.listProposals(walletId, status);
        successResponse(res, proposals);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/multisig/proposals/:id', (req, res) => {
    try {
        const proposal = multisig_1.multiSigCoordinator.getProposal(req.params.id);
        if (!proposal) {
            res.status(404).json({ code: 404, message: 'Proposal not found' });
            return;
        }
        successResponse(res, proposal);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/multisig/proposals/:id/sign', (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.signatureSchema, { ...req.body, proposalId: req.params.id });
        const proposal = multisig_1.multiSigCoordinator.addSignature(params.proposalId, params.signature, params.signer);
        successResponse(res, proposal);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/multisig/proposals/:id/execute', async (req, res) => {
    try {
        const result = await multisig_1.multiSigCoordinator.executeProposal(req.params.id);
        successResponse(res, result);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/zkp/circuits', (req, res) => {
    try {
        const { circuitId, verificationKey, description } = req.body;
        const circuit = zkp_1.zkProofVerifier.registerCircuit(circuitId, verificationKey, description);
        successResponse(res, circuit, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/zkp/circuits', (_req, res) => {
    try {
        const circuits = zkp_1.zkProofVerifier.listCircuits();
        successResponse(res, circuits);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/zkp/verify', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.zkProofSchema, req.body);
        const result = await zkp_1.zkProofVerifier.verifyProof(params);
        successResponse(res, result);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/zkp/history', (req, res) => {
    try {
        const { circuitId, limit } = req.query;
        const history = zkp_1.zkProofVerifier.getVerificationHistory(circuitId, parseInt(limit) || 100);
        successResponse(res, history);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/zkp/stats', (req, res) => {
    try {
        const { circuitId } = req.query;
        const stats = zkp_1.zkProofVerifier.getVerificationStats(circuitId);
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/events/listeners', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.eventListenerSchema, req.body);
        const listener = await events_1.contractEventListener.createListener(params);
        successResponse(res, listener, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/events/listeners', (req, res) => {
    try {
        const { chainId, active } = req.query;
        const listeners = events_1.contractEventListener.listListeners(chainId ? (0, utils_1.asChainId)(parseInt(chainId)) : undefined, active !== undefined ? active === 'true' : undefined);
        successResponse(res, listeners);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/events/listeners/:id/start', async (req, res) => {
    try {
        const listener = await events_1.contractEventListener.startListener(req.params.id);
        successResponse(res, listener);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/events/listeners/:id/stop', (req, res) => {
    try {
        const listener = events_1.contractEventListener.stopListener(req.params.id);
        successResponse(res, listener);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.delete('/events/listeners/:id', (req, res) => {
    try {
        const deleted = events_1.contractEventListener.deleteListener(req.params.id);
        if (!deleted) {
            res.status(404).json({ code: 404, message: 'Listener not found' });
            return;
        }
        successResponse(res, { deleted: true });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/events/historical', async (req, res) => {
    try {
        const events = await events_1.contractEventListener.fetchHistoricalEvents(req.body);
        successResponse(res, events);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/events/stats', (_req, res) => {
    try {
        const stats = events_1.contractEventListener.getStats();
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/build', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.transactionRequestSchema, req.body);
        const result = await transaction_1.transactionBuilder.buildTransaction(params, req.body.options);
        successResponse(res, result);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/:id/sign', async (req, res) => {
    try {
        const { from } = req.body;
        const signed = await transaction_1.transactionBuilder.signTransaction(req.params.id, from);
        successResponse(res, signed);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/message/sign', async (req, res) => {
    try {
        const { message, from } = req.body;
        const result = await transaction_1.transactionBuilder.signMessage(message, from);
        successResponse(res, result);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/message/verify', (req, res) => {
    try {
        const { message, signature, address } = req.body;
        const valid = transaction_1.transactionBuilder.verifySignature(message, signature, address);
        successResponse(res, { valid });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/transactions/pending', (_req, res) => {
    try {
        const txs = transaction_1.transactionBuilder.listPendingTransactions();
        successResponse(res, txs);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/transactions/signed', (req, res) => {
    try {
        const { chainId, from } = req.query;
        const txs = transaction_1.transactionBuilder.listSignedTransactions(chainId ? (0, utils_1.asChainId)(parseInt(chainId)) : undefined, from);
        successResponse(res, txs);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/wallets', (req, res) => {
    try {
        const { privateKey, chainId } = req.body;
        const address = transaction_1.transactionBuilder.addWallet(privateKey, chainId);
        successResponse(res, { address }, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/transactions/wallets', (_req, res) => {
    try {
        const wallets = transaction_1.transactionBuilder.listWallets();
        successResponse(res, wallets);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/transactions/strategies', (req, res) => {
    try {
        const strategy = transaction_1.transactionBuilder.createMultiSigStrategy(req.body);
        successResponse(res, strategy, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/transactions/strategies', (_req, res) => {
    try {
        const strategies = transaction_1.transactionBuilder.listMultiSigStrategies();
        successResponse(res, strategies);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/crosschain/messages', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.crossChainMessageSchema, req.body);
        const message = await crosschain_1.crossChainBridge.createCrossChainMessage(params);
        successResponse(res, message, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/crosschain/messages', (req, res) => {
    try {
        const messages = crosschain_1.crossChainBridge.listMessages(req.query);
        successResponse(res, messages);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/crosschain/messages/:id', (req, res) => {
    try {
        const message = crosschain_1.crossChainBridge.getMessage(req.params.id);
        if (!message) {
            res.status(404).json({ code: 404, message: 'Message not found' });
            return;
        }
        successResponse(res, message);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/crosschain/messages/:id/lock', async (req, res) => {
    try {
        const { bridgeId } = req.body;
        const message = await crosschain_1.crossChainBridge.lockAssets(req.params.id, bridgeId);
        successResponse(res, message);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/crosschain/messages/:id/mint', async (req, res) => {
    try {
        const { requiredSignatures } = req.body;
        const message = await crosschain_1.crossChainBridge.mintAssets(req.params.id, requiredSignatures);
        successResponse(res, message);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/crosschain/messages/:id/confirm', async (req, res) => {
    try {
        const message = await crosschain_1.crossChainBridge.confirmMessage(req.params.id);
        successResponse(res, message);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/crosschain/messages/:id/signature', (req, res) => {
    try {
        const { signature, relayer } = req.body;
        const message = crosschain_1.crossChainBridge.addRelayerSignature(req.params.id, signature, relayer);
        successResponse(res, message);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/crosschain/messages/:id/atomicity', (req, res) => {
    try {
        const result = crosschain_1.crossChainBridge.verifyAtomicity(req.params.id);
        successResponse(res, result);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/crosschain/stats', (_req, res) => {
    try {
        const stats = crosschain_1.crossChainBridge.getStats();
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/hdwallet/create', async (req, res) => {
    try {
        const wallet = await hdwallet_1.hdWalletManager.createHDWallet(req.body);
        successResponse(res, wallet, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/hdwallet/wallets', (_req, res) => {
    try {
        const wallets = hdwallet_1.hdWalletManager.listHDWallets();
        successResponse(res, wallets);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/hdwallet/derive', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.deriveAddressSchema, req.body);
        const { walletId, ...options } = params;
        const addresses = await hdwallet_1.hdWalletManager.deriveAddresses(walletId, options);
        successResponse(res, addresses);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/hdwallet/addresses', (req, res) => {
    try {
        const { walletId } = req.query;
        const addresses = hdwallet_1.hdWalletManager.listDerivedAddresses(walletId);
        successResponse(res, addresses);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.put('/hdwallet/addresses/:path', (req, res) => {
    try {
        const address = hdwallet_1.hdWalletManager.updateAddressMetadata(req.params.path, req.body);
        successResponse(res, address);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/hdwallet/addressbook', (req, res) => {
    try {
        const entry = hdwallet_1.hdWalletManager.addToAddressBook(req.body);
        successResponse(res, entry, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/hdwallet/addressbook', (req, res) => {
    try {
        const entries = hdwallet_1.hdWalletManager.listAddressBook(req.query);
        successResponse(res, entries);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.put('/hdwallet/addressbook/:id', (req, res) => {
    try {
        const entry = hdwallet_1.hdWalletManager.updateAddressBookEntry(req.params.id, req.body);
        successResponse(res, entry);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.delete('/hdwallet/addressbook/:id', (req, res) => {
    try {
        const deleted = hdwallet_1.hdWalletManager.removeFromAddressBook(req.params.id);
        if (!deleted) {
            res.status(404).json({ code: 404, message: 'Entry not found' });
            return;
        }
        successResponse(res, { deleted: true });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/hdwallet/search', (req, res) => {
    try {
        const { q } = req.query;
        const results = hdwallet_1.hdWalletManager.searchAddresses(q);
        successResponse(res, results);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/hdwallet/mnemonic', (_req, res) => {
    try {
        const mnemonic = hdwallet_1.hdWalletManager.generateMnemonic();
        successResponse(res, { mnemonic });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/storage/upload', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.storageUploadSchema, req.body);
        const result = await storage_1.decentralizedStorage.upload(params);
        successResponse(res, result, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/storage/:cid', async (req, res) => {
    try {
        const { network } = req.query;
        const content = await storage_1.decentralizedStorage.download(req.params.cid, network);
        res.set('Content-Type', 'application/octet-stream');
        res.send(Buffer.from(content));
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/storage/:cid/json', async (req, res) => {
    try {
        const { network } = req.query;
        const content = await storage_1.decentralizedStorage.downloadAsJSON(req.params.cid, network);
        successResponse(res, content);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/storage/:cid/pin', async (req, res) => {
    try {
        const { network } = req.query;
        const status = await storage_1.decentralizedStorage.pin(req.params.cid, network);
        successResponse(res, status);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.delete('/storage/:cid/pin', async (req, res) => {
    try {
        const { network } = req.query;
        const result = await storage_1.decentralizedStorage.unpin(req.params.cid, network);
        successResponse(res, { unpinned: result });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/storage/:cid/status', async (req, res) => {
    try {
        const { network } = req.query;
        const status = await storage_1.decentralizedStorage.getPinStatus(req.params.cid, network);
        successResponse(res, status);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/storage', (req, res) => {
    try {
        const { network } = req.query;
        const contents = storage_1.decentralizedStorage.listContents(network);
        successResponse(res, contents);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/storage/stats', (_req, res) => {
    try {
        const stats = storage_1.decentralizedStorage.getStats();
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/indexer/start', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.blockIndexSchema, req.body);
        const indexingParams = {
            ...params,
            toBlock: params.toBlock || 'latest',
            includeTransactions: params.includeTransactions ?? true,
            includeLogs: params.includeLogs ?? true,
        };
        const progress = await indexer_1.chainDataIndexer.startIndexing(indexingParams);
        successResponse(res, progress, 201);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/tasks', (req, res) => {
    try {
        const { chainId, status } = req.query;
        const tasks = indexer_1.chainDataIndexer.listIndexingTasks(chainId ? (0, utils_1.asChainId)(parseInt(chainId)) : undefined, status);
        successResponse(res, tasks);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/tasks/:id', (req, res) => {
    try {
        const progress = indexer_1.chainDataIndexer.getIndexingProgress(req.params.id);
        if (!progress) {
            res.status(404).json({ code: 404, message: 'Task not found' });
            return;
        }
        successResponse(res, progress);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/indexer/tasks/:id/pause', (req, res) => {
    try {
        const result = indexer_1.chainDataIndexer.pauseIndexing(req.params.id);
        successResponse(res, { paused: result });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/indexer/tasks/:id/resume', (req, res) => {
    try {
        const result = indexer_1.chainDataIndexer.resumeIndexing(req.params.id);
        successResponse(res, { resumed: result });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/indexer/tasks/:id/cancel', (req, res) => {
    try {
        const result = indexer_1.chainDataIndexer.cancelIndexing(req.params.id);
        successResponse(res, { cancelled: result });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/blocks/:chainId/:blockNumber', (req, res) => {
    try {
        const block = indexer_1.chainDataIndexer.getBlock(parseInt(req.params.chainId), parseInt(req.params.blockNumber));
        if (!block) {
            res.status(404).json({ code: 404, message: 'Block not found' });
            return;
        }
        successResponse(res, block);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/transactions/:hash', (req, res) => {
    try {
        const tx = indexer_1.chainDataIndexer.getTransaction(req.params.hash);
        if (!tx) {
            res.status(404).json({ code: 404, message: 'Transaction not found' });
            return;
        }
        successResponse(res, tx);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/transactions', (req, res) => {
    try {
        const { chainId, from, to } = req.query;
        const txs = indexer_1.chainDataIndexer.listTransactions(chainId ? (0, utils_1.asChainId)(parseInt(chainId)) : undefined, from, to);
        successResponse(res, txs);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/logs/:chainId', (req, res) => {
    try {
        const { fromBlock, toBlock, address, eventName } = req.query;
        const logs = indexer_1.chainDataIndexer.getLogs(parseInt(req.params.chainId), fromBlock ? parseInt(fromBlock) : undefined, toBlock ? parseInt(toBlock) : undefined, address, eventName);
        successResponse(res, logs);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/indexer/stats', (req, res) => {
    try {
        const { chainId } = req.query;
        const stats = indexer_1.chainDataIndexer.getStats(chainId ? (0, utils_1.asChainId)(parseInt(chainId)) : undefined);
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/chains', (_req, res) => {
    try {
        const chains = chainadapter_1.chainAdapter.listSupportedChains();
        successResponse(res, chains);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/block-number', async (req, res) => {
    try {
        const blockNumber = await chainadapter_1.chainAdapter.getBlockNumber(parseInt(req.params.chainId));
        successResponse(res, { blockNumber });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/block/:blockNumber', async (req, res) => {
    try {
        const { includeTransactions } = req.query;
        const block = await chainadapter_1.chainAdapter.getBlock(parseInt(req.params.chainId), parseInt(req.params.blockNumber), includeTransactions === 'true');
        successResponse(res, block);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/transaction/:hash', async (req, res) => {
    try {
        const tx = await chainadapter_1.chainAdapter.getTransaction(parseInt(req.params.chainId), req.params.hash);
        successResponse(res, tx);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/balance/:address', async (req, res) => {
    try {
        const balance = await chainadapter_1.chainAdapter.getBalance(parseInt(req.params.chainId), req.params.address);
        successResponse(res, { balance });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/nonce/:address', async (req, res) => {
    try {
        const nonce = await chainadapter_1.chainAdapter.getNonce(parseInt(req.params.chainId), req.params.address);
        successResponse(res, { nonce });
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/chain/:chainId/send', async (req, res) => {
    try {
        const { rawTransaction } = req.body;
        const submission = await chainadapter_1.chainAdapter.sendTransaction(parseInt(req.params.chainId), rawTransaction);
        successResponse(res, submission);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/chain/:chainId/stats', async (req, res) => {
    try {
        const stats = await chainadapter_1.chainAdapter.getChainStats(parseInt(req.params.chainId));
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/estimate', async (req, res) => {
    try {
        const params = (0, validation_1.validateSchema)(validation_2.gasEstimateSchema, req.query);
        const estimate = await gas_1.gasEstimator.estimateGas(params);
        successResponse(res, estimate);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/:chainId/history', async (req, res) => {
    try {
        const { limit } = req.query;
        const history = await gas_1.gasEstimator.getGasHistory(parseInt(req.params.chainId), limit ? parseInt(limit) : 50);
        successResponse(res, history);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/:chainId/prediction', async (req, res) => {
    try {
        const prediction = await gas_1.gasEstimator.getGasPrediction(parseInt(req.params.chainId));
        successResponse(res, prediction);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/:chainId/recommendation', async (req, res) => {
    try {
        const { urgency } = req.query;
        const recommendation = await gas_1.gasEstimator.getGasRecommendation(parseInt(req.params.chainId), urgency);
        successResponse(res, recommendation);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/:chainId/stats', async (req, res) => {
    try {
        const stats = await gas_1.gasEstimator.getHistoricalGasStats(parseInt(req.params.chainId));
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/gas/transaction-cost', async (req, res) => {
    try {
        const cost = await gas_1.gasEstimator.estimateTransactionCost(req.body);
        successResponse(res, cost);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.post('/gas/comparison', async (req, res) => {
    try {
        const { chains } = req.body;
        const comparison = await gas_1.gasEstimator.getGasComparison(chains);
        successResponse(res, comparison);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/gas/stats', (_req, res) => {
    try {
        const stats = gas_1.gasEstimator.getStats();
        successResponse(res, stats);
    }
    catch (error) {
        handleError(res, error);
    }
});
router.get('/health', (_req, res) => {
    successResponse(res, { status: 'healthy', timestamp: (0, utils_1.now)() });
});
router.get('/metrics', (_req, res) => {
    const snapshot = {
        snapshot_id: (0, utils_1.generateId)('snap'),
        timestamp: (0, utils_1.now)(),
        metrics: {
            throughput: 1500,
            latency_p99: 250,
            error_rate: 0.001,
        },
        dimensions: {
            host: process.env.HOSTNAME || 'node-1',
            region: process.env.REGION || 'cn-east',
        },
    };
    successResponse(res, snapshot);
});
exports.default = router;
//# sourceMappingURL=routes.js.map