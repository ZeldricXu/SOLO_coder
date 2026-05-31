import { Request, Response, NextFunction } from 'express';
import { CrossChainBridgeService } from './crossChainBridge.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';
import { TransferStatus } from '../../types';

export class CrossChainBridgeController {
  private service: CrossChainBridgeService;

  constructor() {
    this.service = new CrossChainBridgeService();
  }

  initiateTransfer = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.transferRequest, req.body);
      const result = await this.service.initiateTransfer(data);
      ResponseUtils.created(res, result, 'Transfer initiated successfully');
    } catch (error) {
      next(error);
    }
  };

  confirmLock = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { txHash, signatures } = req.body;

      if (!txHash) {
        return ResponseUtils.badRequest(res, 'Transaction hash is required');
      }

      if (!Array.isArray(signatures) || signatures.length === 0) {
        return ResponseUtils.badRequest(res, 'Signatures are required');
      }

      const result = await this.service.confirmLock(id, txHash, signatures);
      ResponseUtils.success(res, result, 'Lock confirmed successfully');
    } catch (error) {
      next(error);
    }
  };

  validateMessage = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { proof } = req.body;

      if (!proof) {
        return ResponseUtils.badRequest(res, 'Proof is required');
      }

      const result = await this.service.validateMessage(id, proof);
      ResponseUtils.success(res, result, 'Message validated successfully');
    } catch (error) {
      next(error);
    }
  };

  executeMint = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const result = await this.service.executeMint(id);
      ResponseUtils.success(res, result, 'Mint executed successfully');
    } catch (error) {
      next(error);
    }
  };

  confirmTransfer = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const result = await this.service.confirmTransfer(id);
      ResponseUtils.success(res, result, 'Transfer confirmed successfully');
    } catch (error) {
      next(error);
    }
  };

  getTransfer = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const transfer = await this.service.getTransfer(id);
      ResponseUtils.success(res, transfer);
    } catch (error) {
      next(error);
    }
  };

  getTransfers = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const filters = {
        sourceChainId: req.query.sourceChainId ? Number(req.query.sourceChainId) : undefined,
        targetChainId: req.query.targetChainId ? Number(req.query.targetChainId) : undefined,
        sourceAddress: req.query.sourceAddress as string,
        targetAddress: req.query.targetAddress as string,
        status: req.query.status as TransferStatus,
        page: Number(req.query.page) || 1,
        pageSize: Number(req.query.pageSize) || 20,
      };

      const result = await this.service.getTransfers(filters);
      ResponseUtils.paginated(
        res,
        result.items,
        result.total,
        filters.page,
        filters.pageSize
      );
    } catch (error) {
      next(error);
    }
  };

  getPendingTransfers = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const transfers = await this.service.getPendingTransfers(Number(chainId));
      ResponseUtils.success(res, transfers);
    } catch (error) {
      next(error);
    }
  };
}

export const crossChainBridgeController = new CrossChainBridgeController();
export default crossChainBridgeController;
