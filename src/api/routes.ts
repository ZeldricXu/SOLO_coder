import express, { Request, Response } from 'express';
import { validateSchema, ValidationError } from '../common/validation';
import {
  createResourceSchema,
  batchOperationSchema,
  multiSigProposalSchema,
  signatureSchema,
  zkProofSchema,
  eventListenerSchema,
  transactionRequestSchema,
  crossChainMessageSchema,
  deriveAddressSchema,
  storageUploadSchema,
  blockIndexSchema,
  gasEstimateSchema,
} from '../common/validation';
import { generateId, now, asChainId } from '../common/utils';
import { LoggerContext } from '../common/logger';
import { ApiResponse, CoreEntity, RunInstance, MetricsSnapshot, ChainId } from '../types';
import { multiSigCoordinator } from '../modules/multisig';
import { zkProofVerifier } from '../modules/zkp';
import { contractEventListener } from '../modules/events';
import { transactionBuilder } from '../modules/transaction';
import { crossChainBridge } from '../modules/crosschain';
import { hdWalletManager } from '../modules/hdwallet';
import { decentralizedStorage } from '../modules/storage';
import { chainDataIndexer } from '../modules/indexer';
import { chainAdapter } from '../modules/chainadapter';
import { gasEstimator } from '../modules/gas';

const router = express.Router();
const logger = new LoggerContext({ module: 'API' });

function handleError(res: Response, error: unknown): void {
  if (error instanceof ValidationError) {
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

  logger.error('Unknown API error', error as Error);
  res.status(500).json({
    code: 500,
    message: 'Internal server error',
  });
}

function successResponse<T>(res: Response, data: T, code: number = 200): void {
  res.status(code).json({ code, data });
}

router.post('/resources', (req: Request, res: Response) => {
  try {
    const params = validateSchema(createResourceSchema, req.body);
    const resource: CoreEntity = {
      id: generateId('rsc'),
      type: params.type,
      status: 'provisioning',
      attributes: params.config,
      created_at: now(),
      updated_at: now(),
    };
    successResponse(res, { id: resource.id, status: resource.status }, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/resources/:id/status', (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const run: RunInstance = {
      run_id: generateId('run'),
      entity_id: id,
      phase: 'initializing',
      progress: 0.8,
      started_at: now(),
      completed_at: null,
      error_detail: null,
    };
    successResponse(res, { id, status: 'running', progress: run.progress });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/resources/batch', (req: Request, res: Response) => {
  try {
    const params = validateSchema(batchOperationSchema, req.body);
    const results = params.operations.map((op) => ({
      id: op.id,
      action: op.action,
      status: 'success',
    }));
    successResponse(res, { batch_id: generateId('batch'), results });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/multisig/wallets', (req: Request, res: Response) => {
  try {
    const { walletId, signers, requiredSignatures } = req.body;
    const wallet = multiSigCoordinator.createWallet(walletId, signers, requiredSignatures);
    successResponse(res, wallet, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/multisig/wallets', (_req: Request, res: Response) => {
  try {
    const wallets = multiSigCoordinator.listWallets();
    successResponse(res, wallets);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/multisig/proposals', (req: Request, res: Response) => {
  try {
    const params = validateSchema(multiSigProposalSchema, req.body);
    const proposal = multiSigCoordinator.createProposal(params);
    successResponse(res, proposal, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/multisig/proposals', (req: Request, res: Response) => {
  try {
    const { walletId, status } = req.query;
    const proposals = multiSigCoordinator.listProposals(
      walletId as string,
      status as 'pending' | 'approved' | 'executed' | 'rejected'
    );
    successResponse(res, proposals);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/multisig/proposals/:id', (req: Request, res: Response) => {
  try {
    const proposal = multiSigCoordinator.getProposal(req.params.id);
    if (!proposal) {
      res.status(404).json({ code: 404, message: 'Proposal not found' });
      return;
    }
    successResponse(res, proposal);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/multisig/proposals/:id/sign', (req: Request, res: Response) => {
  try {
    const params = validateSchema(signatureSchema, { ...req.body, proposalId: req.params.id });
    const proposal = multiSigCoordinator.addSignature(params.proposalId, params.signature, params.signer);
    successResponse(res, proposal);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/multisig/proposals/:id/execute', async (req: Request, res: Response) => {
  try {
    const result = await multiSigCoordinator.executeProposal(req.params.id);
    successResponse(res, result);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/zkp/circuits', (req: Request, res: Response) => {
  try {
    const { circuitId, verificationKey, description } = req.body;
    const circuit = zkProofVerifier.registerCircuit(circuitId, verificationKey, description);
    successResponse(res, circuit, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/zkp/circuits', (_req: Request, res: Response) => {
  try {
    const circuits = zkProofVerifier.listCircuits();
    successResponse(res, circuits);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/zkp/verify', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(zkProofSchema, req.body);
    const result = await zkProofVerifier.verifyProof(params);
    successResponse(res, result);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/zkp/history', (req: Request, res: Response) => {
  try {
    const { circuitId, limit } = req.query;
    const history = zkProofVerifier.getVerificationHistory(
      circuitId as string,
      parseInt(limit as string) || 100
    );
    successResponse(res, history);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/zkp/stats', (req: Request, res: Response) => {
  try {
    const { circuitId } = req.query;
    const stats = zkProofVerifier.getVerificationStats(circuitId as string);
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/events/listeners', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(eventListenerSchema, req.body);
    const listener = await contractEventListener.createListener(params);
    successResponse(res, listener, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/events/listeners', (req: Request, res: Response) => {
  try {
    const { chainId, active } = req.query;
    const listeners = contractEventListener.listListeners(
      chainId ? asChainId(parseInt(chainId as string)) : undefined,
      active !== undefined ? active === 'true' : undefined
    );
    successResponse(res, listeners);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/events/listeners/:id/start', async (req: Request, res: Response) => {
  try {
    const listener = await contractEventListener.startListener(req.params.id);
    successResponse(res, listener);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/events/listeners/:id/stop', (req: Request, res: Response) => {
  try {
    const listener = contractEventListener.stopListener(req.params.id);
    successResponse(res, listener);
  } catch (error) {
    handleError(res, error);
  }
});

router.delete('/events/listeners/:id', (req: Request, res: Response) => {
  try {
    const deleted = contractEventListener.deleteListener(req.params.id);
    if (!deleted) {
      res.status(404).json({ code: 404, message: 'Listener not found' });
      return;
    }
    successResponse(res, { deleted: true });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/events/historical', async (req: Request, res: Response) => {
  try {
    const events = await contractEventListener.fetchHistoricalEvents(req.body);
    successResponse(res, events);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/events/stats', (_req: Request, res: Response) => {
  try {
    const stats = contractEventListener.getStats();
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/build', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(transactionRequestSchema, req.body);
    const result = await transactionBuilder.buildTransaction(params, req.body.options);
    successResponse(res, result);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/:id/sign', async (req: Request, res: Response) => {
  try {
    const { from } = req.body;
    const signed = await transactionBuilder.signTransaction(req.params.id, from);
    successResponse(res, signed);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/message/sign', async (req: Request, res: Response) => {
  try {
    const { message, from } = req.body;
    const result = await transactionBuilder.signMessage(message, from);
    successResponse(res, result);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/message/verify', (req: Request, res: Response) => {
  try {
    const { message, signature, address } = req.body;
    const valid = transactionBuilder.verifySignature(message, signature, address);
    successResponse(res, { valid });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/transactions/pending', (_req: Request, res: Response) => {
  try {
    const txs = transactionBuilder.listPendingTransactions();
    successResponse(res, txs);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/transactions/signed', (req: Request, res: Response) => {
  try {
    const { chainId, from } = req.query;
    const txs = transactionBuilder.listSignedTransactions(
      chainId ? asChainId(parseInt(chainId as string)) : undefined,
      from as string
    );
    successResponse(res, txs);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/wallets', (req: Request, res: Response) => {
  try {
    const { privateKey, chainId } = req.body;
    const address = transactionBuilder.addWallet(privateKey, chainId);
    successResponse(res, { address }, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/transactions/wallets', (_req: Request, res: Response) => {
  try {
    const wallets = transactionBuilder.listWallets();
    successResponse(res, wallets);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/transactions/strategies', (req: Request, res: Response) => {
  try {
    const strategy = transactionBuilder.createMultiSigStrategy(req.body);
    successResponse(res, strategy, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/transactions/strategies', (_req: Request, res: Response) => {
  try {
    const strategies = transactionBuilder.listMultiSigStrategies();
    successResponse(res, strategies);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/crosschain/messages', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(crossChainMessageSchema, req.body);
    const message = await crossChainBridge.createCrossChainMessage(params);
    successResponse(res, message, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/crosschain/messages', (req: Request, res: Response) => {
  try {
    const messages = crossChainBridge.listMessages(req.query);
    successResponse(res, messages);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/crosschain/messages/:id', (req: Request, res: Response) => {
  try {
    const message = crossChainBridge.getMessage(req.params.id);
    if (!message) {
      res.status(404).json({ code: 404, message: 'Message not found' });
      return;
    }
    successResponse(res, message);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/crosschain/messages/:id/lock', async (req: Request, res: Response) => {
  try {
    const { bridgeId } = req.body;
    const message = await crossChainBridge.lockAssets(req.params.id, bridgeId);
    successResponse(res, message);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/crosschain/messages/:id/mint', async (req: Request, res: Response) => {
  try {
    const { requiredSignatures } = req.body;
    const message = await crossChainBridge.mintAssets(req.params.id, requiredSignatures);
    successResponse(res, message);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/crosschain/messages/:id/confirm', async (req: Request, res: Response) => {
  try {
    const message = await crossChainBridge.confirmMessage(req.params.id);
    successResponse(res, message);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/crosschain/messages/:id/signature', (req: Request, res: Response) => {
  try {
    const { signature, relayer } = req.body;
    const message = crossChainBridge.addRelayerSignature(req.params.id, signature, relayer);
    successResponse(res, message);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/crosschain/messages/:id/atomicity', (req: Request, res: Response) => {
  try {
    const result = crossChainBridge.verifyAtomicity(req.params.id);
    successResponse(res, result);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/crosschain/stats', (_req: Request, res: Response) => {
  try {
    const stats = crossChainBridge.getStats();
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/hdwallet/create', async (req: Request, res: Response) => {
  try {
    const wallet = await hdWalletManager.createHDWallet(req.body);
    successResponse(res, wallet, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/hdwallet/wallets', (_req: Request, res: Response) => {
  try {
    const wallets = hdWalletManager.listHDWallets();
    successResponse(res, wallets);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/hdwallet/derive', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(deriveAddressSchema, req.body);
    const { walletId, ...options } = params;
    const addresses = await hdWalletManager.deriveAddresses(walletId, options);
    successResponse(res, addresses);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/hdwallet/addresses', (req: Request, res: Response) => {
  try {
    const { walletId } = req.query;
    const addresses = hdWalletManager.listDerivedAddresses(walletId as string);
    successResponse(res, addresses);
  } catch (error) {
    handleError(res, error);
  }
});

router.put('/hdwallet/addresses/:path', (req: Request, res: Response) => {
  try {
    const address = hdWalletManager.updateAddressMetadata(req.params.path, req.body);
    successResponse(res, address);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/hdwallet/addressbook', (req: Request, res: Response) => {
  try {
    const entry = hdWalletManager.addToAddressBook(req.body);
    successResponse(res, entry, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/hdwallet/addressbook', (req: Request, res: Response) => {
  try {
    const entries = hdWalletManager.listAddressBook(req.query);
    successResponse(res, entries);
  } catch (error) {
    handleError(res, error);
  }
});

router.put('/hdwallet/addressbook/:id', (req: Request, res: Response) => {
  try {
    const entry = hdWalletManager.updateAddressBookEntry(req.params.id, req.body);
    successResponse(res, entry);
  } catch (error) {
    handleError(res, error);
  }
});

router.delete('/hdwallet/addressbook/:id', (req: Request, res: Response) => {
  try {
    const deleted = hdWalletManager.removeFromAddressBook(req.params.id);
    if (!deleted) {
      res.status(404).json({ code: 404, message: 'Entry not found' });
      return;
    }
    successResponse(res, { deleted: true });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/hdwallet/search', (req: Request, res: Response) => {
  try {
    const { q } = req.query;
    const results = hdWalletManager.searchAddresses(q as string);
    successResponse(res, results);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/hdwallet/mnemonic', (_req: Request, res: Response) => {
  try {
    const mnemonic = hdWalletManager.generateMnemonic();
    successResponse(res, { mnemonic });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/storage/upload', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(storageUploadSchema, req.body);
    const result = await decentralizedStorage.upload(params);
    successResponse(res, result, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/storage/:cid', async (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const content = await decentralizedStorage.download(
      req.params.cid,
      network as 'ipfs' | 'arweave'
    );
    res.set('Content-Type', 'application/octet-stream');
    res.send(Buffer.from(content));
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/storage/:cid/json', async (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const content = await decentralizedStorage.downloadAsJSON(
      req.params.cid,
      network as 'ipfs' | 'arweave'
    );
    successResponse(res, content);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/storage/:cid/pin', async (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const status = await decentralizedStorage.pin(
      req.params.cid,
      network as 'ipfs' | 'arweave'
    );
    successResponse(res, status);
  } catch (error) {
    handleError(res, error);
  }
});

router.delete('/storage/:cid/pin', async (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const result = await decentralizedStorage.unpin(
      req.params.cid,
      network as 'ipfs' | 'arweave'
    );
    successResponse(res, { unpinned: result });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/storage/:cid/status', async (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const status = await decentralizedStorage.getPinStatus(
      req.params.cid,
      network as 'ipfs' | 'arweave'
    );
    successResponse(res, status);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/storage', (req: Request, res: Response) => {
  try {
    const { network } = req.query;
    const contents = decentralizedStorage.listContents(network as 'ipfs' | 'arweave');
    successResponse(res, contents);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/storage/stats', (_req: Request, res: Response) => {
  try {
    const stats = decentralizedStorage.getStats();
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/indexer/start', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(blockIndexSchema, req.body);
    const indexingParams = {
      ...params,
      toBlock: params.toBlock || 'latest',
      includeTransactions: params.includeTransactions ?? true,
      includeLogs: params.includeLogs ?? true,
    };
    const progress = await chainDataIndexer.startIndexing(indexingParams);
    successResponse(res, progress, 201);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/tasks', (req: Request, res: Response) => {
  try {
    const { chainId, status } = req.query;
    const tasks = chainDataIndexer.listIndexingTasks(
      chainId ? asChainId(parseInt(chainId as string)) : undefined,
      status as 'running' | 'paused' | 'completed' | 'failed'
    );
    successResponse(res, tasks);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/tasks/:id', (req: Request, res: Response) => {
  try {
    const progress = chainDataIndexer.getIndexingProgress(req.params.id);
    if (!progress) {
      res.status(404).json({ code: 404, message: 'Task not found' });
      return;
    }
    successResponse(res, progress);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/indexer/tasks/:id/pause', (req: Request, res: Response) => {
  try {
    const result = chainDataIndexer.pauseIndexing(req.params.id);
    successResponse(res, { paused: result });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/indexer/tasks/:id/resume', (req: Request, res: Response) => {
  try {
    const result = chainDataIndexer.resumeIndexing(req.params.id);
    successResponse(res, { resumed: result });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/indexer/tasks/:id/cancel', (req: Request, res: Response) => {
  try {
    const result = chainDataIndexer.cancelIndexing(req.params.id);
    successResponse(res, { cancelled: result });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/blocks/:chainId/:blockNumber', (req: Request, res: Response) => {
  try {
    const block = chainDataIndexer.getBlock(
      parseInt(req.params.chainId) as ChainId,
      parseInt(req.params.blockNumber)
    );
    if (!block) {
      res.status(404).json({ code: 404, message: 'Block not found' });
      return;
    }
    successResponse(res, block);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/transactions/:hash', (req: Request, res: Response) => {
  try {
    const tx = chainDataIndexer.getTransaction(req.params.hash);
    if (!tx) {
      res.status(404).json({ code: 404, message: 'Transaction not found' });
      return;
    }
    successResponse(res, tx);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/transactions', (req: Request, res: Response) => {
  try {
    const { chainId, from, to } = req.query;
    const txs = chainDataIndexer.listTransactions(
      chainId ? asChainId(parseInt(chainId as string)) : undefined,
      from as string,
      to as string
    );
    successResponse(res, txs);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/logs/:chainId', (req: Request, res: Response) => {
  try {
    const { fromBlock, toBlock, address, eventName } = req.query;
    const logs = chainDataIndexer.getLogs(
      parseInt(req.params.chainId) as ChainId,
      fromBlock ? parseInt(fromBlock as string) : undefined,
      toBlock ? parseInt(toBlock as string) : undefined,
      address as string,
      eventName as string
    );
    successResponse(res, logs);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/indexer/stats', (req: Request, res: Response) => {
  try {
    const { chainId } = req.query;
    const stats = chainDataIndexer.getStats(
      chainId ? asChainId(parseInt(chainId as string)) : undefined
    );
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/chains', (_req: Request, res: Response) => {
  try {
    const chains = chainAdapter.listSupportedChains();
    successResponse(res, chains);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/block-number', async (req: Request, res: Response) => {
  try {
    const blockNumber = await chainAdapter.getBlockNumber(parseInt(req.params.chainId) as ChainId);
    successResponse(res, { blockNumber });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/block/:blockNumber', async (req: Request, res: Response) => {
  try {
    const { includeTransactions } = req.query;
    const block = await chainAdapter.getBlock(
      parseInt(req.params.chainId) as ChainId,
      parseInt(req.params.blockNumber),
      includeTransactions === 'true'
    );
    successResponse(res, block);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/transaction/:hash', async (req: Request, res: Response) => {
  try {
    const tx = await chainAdapter.getTransaction(
      parseInt(req.params.chainId) as ChainId,
      req.params.hash
    );
    successResponse(res, tx);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/balance/:address', async (req: Request, res: Response) => {
  try {
    const balance = await chainAdapter.getBalance(
      parseInt(req.params.chainId) as ChainId,
      req.params.address
    );
    successResponse(res, { balance });
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/nonce/:address', async (req: Request, res: Response) => {
  try {
    const nonce = await chainAdapter.getNonce(
      parseInt(req.params.chainId) as ChainId,
      req.params.address
    );
    successResponse(res, { nonce });
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/chain/:chainId/send', async (req: Request, res: Response) => {
  try {
    const { rawTransaction } = req.body;
    const submission = await chainAdapter.sendTransaction(
      parseInt(req.params.chainId) as ChainId,
      rawTransaction
    );
    successResponse(res, submission);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/chain/:chainId/stats', async (req: Request, res: Response) => {
  try {
    const stats = await chainAdapter.getChainStats(parseInt(req.params.chainId) as ChainId);
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/estimate', async (req: Request, res: Response) => {
  try {
    const params = validateSchema(gasEstimateSchema, req.query);
    const estimate = await gasEstimator.estimateGas(params);
    successResponse(res, estimate);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/:chainId/history', async (req: Request, res: Response) => {
  try {
    const { limit } = req.query;
    const history = await gasEstimator.getGasHistory(
      parseInt(req.params.chainId) as ChainId,
      limit ? parseInt(limit as string) : 50
    );
    successResponse(res, history);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/:chainId/prediction', async (req: Request, res: Response) => {
  try {
    const prediction = await gasEstimator.getGasPrediction(
      parseInt(req.params.chainId) as ChainId
    );
    successResponse(res, prediction);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/:chainId/recommendation', async (req: Request, res: Response) => {
  try {
    const { urgency } = req.query;
    const recommendation = await gasEstimator.getGasRecommendation(
      parseInt(req.params.chainId) as ChainId,
      urgency as 'low' | 'medium' | 'high'
    );
    successResponse(res, recommendation);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/:chainId/stats', async (req: Request, res: Response) => {
  try {
    const stats = await gasEstimator.getHistoricalGasStats(
      parseInt(req.params.chainId) as ChainId
    );
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/gas/transaction-cost', async (req: Request, res: Response) => {
  try {
    const cost = await gasEstimator.estimateTransactionCost(req.body);
    successResponse(res, cost);
  } catch (error) {
    handleError(res, error);
  }
});

router.post('/gas/comparison', async (req: Request, res: Response) => {
  try {
    const { chains } = req.body;
    const comparison = await gasEstimator.getGasComparison(chains);
    successResponse(res, comparison);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/gas/stats', (_req: Request, res: Response) => {
  try {
    const stats = gasEstimator.getStats();
    successResponse(res, stats);
  } catch (error) {
    handleError(res, error);
  }
});

router.get('/health', (_req: Request, res: Response) => {
  successResponse(res, { status: 'healthy', timestamp: now() });
});

router.get('/metrics', (_req: Request, res: Response) => {
  const snapshot: MetricsSnapshot = {
    snapshot_id: generateId('snap'),
    timestamp: now(),
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

export default router;
