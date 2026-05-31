import { Request, Response, NextFunction } from 'express';
import { ChainAdapterService } from './chainAdapter.service';
import { ResponseUtils } from '../../utils/response';

export class ChainAdapterController {
  private service: ChainAdapterService;

  constructor() {
    this.service = new ChainAdapterService();
  }

  getBlockNumber = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const blockNumber = await this.service.getBlockNumber(Number(chainId));
      ResponseUtils.success(res, { blockNumber: blockNumber.toString() });
    } catch (error) {
      next(error);
    }
  };

  getBlock = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, block } = req.params;
      
      let blockNumberOrHash: number | string;
      if (block.startsWith('0x')) {
        blockNumberOrHash = block;
      } else if (!isNaN(Number(block))) {
        blockNumberOrHash = Number(block);
      } else {
        return ResponseUtils.badRequest(res, 'Invalid block identifier');
      }

      const blockData = await this.service.getBlock(Number(chainId), blockNumberOrHash);
      
      if (!blockData) {
        return ResponseUtils.notFound(res, 'Block not found');
      }

      ResponseUtils.success(res, blockData);
    } catch (error) {
      next(error);
    }
  };

  getTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, hash } = req.params;
      const transaction = await this.service.getTransaction(Number(chainId), hash);
      
      if (!transaction) {
        return ResponseUtils.notFound(res, 'Transaction not found');
      }

      ResponseUtils.success(res, transaction);
    } catch (error) {
      next(error);
    }
  };

  getTransactionReceipt = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, hash } = req.params;
      const receipt = await this.service.getTransactionReceipt(Number(chainId), hash);
      
      if (!receipt) {
        return ResponseUtils.notFound(res, 'Transaction receipt not found');
      }

      ResponseUtils.success(res, receipt);
    } catch (error) {
      next(error);
    }
  };

  getBalance = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, address } = req.params;
      const balance = await this.service.getBalance(Number(chainId), address);
      ResponseUtils.success(res, { balance });
    } catch (error) {
      next(error);
    }
  };

  getNonce = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, address } = req.params;
      const nonce = await this.service.getNonce(Number(chainId), address);
      ResponseUtils.success(res, { nonce });
    } catch (error) {
      next(error);
    }
  };

  call = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { to, data, from } = req.body;

      if (!to) {
        return ResponseUtils.badRequest(res, 'To address is required');
      }

      if (!data) {
        return ResponseUtils.badRequest(res, 'Data is required');
      }

      const result = await this.service.call(Number(chainId), to, data, from);
      ResponseUtils.success(res, { result });
    } catch (error) {
      next(error);
    }
  };

  estimateGas = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { to, data, value, from } = req.body;

      if (!to) {
        return ResponseUtils.badRequest(res, 'To address is required');
      }

      if (!data) {
        return ResponseUtils.badRequest(res, 'Data is required');
      }

      const gasEstimate = await this.service.estimateGas(
        Number(chainId),
        to,
        data,
        value,
        from
      );
      ResponseUtils.success(res, { gasEstimate });
    } catch (error) {
      next(error);
    }
  };

  getGasPrice = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const gasPrice = await this.service.getGasPrice(Number(chainId));
      ResponseUtils.success(res, { gasPrice });
    } catch (error) {
      next(error);
    }
  };

  getFeeData = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const feeData = await this.service.getFeeData(Number(chainId));
      ResponseUtils.success(res, feeData);
    } catch (error) {
      next(error);
    }
  };

  broadcastTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { signedTx } = req.body;

      if (!signedTx) {
        return ResponseUtils.badRequest(res, 'Signed transaction is required');
      }

      const txHash = await this.service.broadcastTransaction(Number(chainId), signedTx);
      ResponseUtils.success(res, { txHash }, 'Transaction broadcast successfully');
    } catch (error) {
      next(error);
    }
  };

  waitForTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, hash } = req.params;
      const { confirmations = 1, timeout = 60000 } = req.body;

      const receipt = await this.service.waitForTransaction(
        Number(chainId),
        hash,
        Number(confirmations),
        Number(timeout)
      );

      if (!receipt) {
        return ResponseUtils.notFound(res, 'Transaction not found or timeout');
      }

      ResponseUtils.success(res, receipt);
    } catch (error) {
      next(error);
    }
  };

  getChainConfig = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const config = await this.service.getChainConfig(Number(chainId));
      ResponseUtils.success(res, config);
    } catch (error) {
      next(error);
    }
  };

  listChainConfigs = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const configs = await this.service.listChainConfigs();
      ResponseUtils.success(res, configs);
    } catch (error) {
      next(error);
    }
  };

  addChainConfig = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const config = await this.service.addChainConfig(req.body);
      ResponseUtils.created(res, config, 'Chain configuration added successfully');
    } catch (error) {
      next(error);
    }
  };

  updateChainConfig = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const config = await this.service.updateChainConfig(Number(chainId)), req.body);
      ResponseUtils.success(res, config, 'Chain configuration updated successfully');
    } catch (error) {
      next(error);
    }
  };
}

export const chainAdapterController = new ChainAdapterController();
export default chainAdapterController;
