import { Request, Response, NextFunction } from 'express';
import { TransactionBuilderService } from './transactionBuilder.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';
import { TransactionStatus } from '../../types';

export class TransactionBuilderController {
  private service: TransactionBuilderService;

  constructor() {
    this.service = new TransactionBuilderService();
  }

  buildTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.transaction, req.body);
      const transaction = await this.service.buildTransaction(data);
      ResponseUtils.created(res, transaction, 'Transaction built successfully');
    } catch (error) {
      next(error);
    }
  };

  signTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { signer, signature } = req.body;

      if (!signer) {
        return ResponseUtils.badRequest(res, 'Signer address is required');
      }

      if (!signature) {
        return ResponseUtils.badRequest(res, 'Signature is required');
      }

      const transaction = await this.service.signTransaction({
        transactionId: id,
        signer,
        signature,
      });
      ResponseUtils.success(res, transaction, 'Transaction signed successfully');
    } catch (error) {
      next(error);
    }
  };

  getTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const transaction = await this.service.getTransaction(id);
      ResponseUtils.success(res, transaction);
    } catch (error) {
      next(error);
    }
  };

  getTransactionByHash = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { hash } = req.params;
      const transaction = await this.service.getTransactionByHash(hash);
      ResponseUtils.success(res, transaction);
    } catch (error) {
      next(error);
    }
  };

  getTransactions = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const filters = {
        chainId: req.query.chainId ? Number(req.query.chainId) : undefined,
        from: req.query.from as string,
        to: req.query.to as string,
        status: req.query.status as TransactionStatus,
        page: Number(req.query.page) || 1,
        pageSize: Number(req.query.pageSize) || 20,
      };

      const result = await this.service.getTransactions(filters);
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

  updateTransactionStatus = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { status, txHash, blockNumber, errorMessage } = req.body;

      if (!status) {
        return ResponseUtils.badRequest(res, 'Status is required');
      }

      const transaction = await this.service.updateTransactionStatus(
        id,
        status,
        txHash,
        blockNumber ? BigInt(blockNumber) : undefined,
        errorMessage
      );
      ResponseUtils.success(res, transaction, 'Transaction status updated');
    } catch (error) {
      next(error);
    }
  };

  buildMultisigTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.transaction, req.body);
      
      if (!data.multisig) {
        return ResponseUtils.badRequest(res, 'Multisig configuration is required');
      }

      const result = await this.service.buildMultisigTransaction(
        data as any
      );
      ResponseUtils.created(res, result, 'Multisig transaction built successfully');
    } catch (error) {
      next(error);
    }
  };

  signMultisigTransaction = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { signer, signature } = req.body;

      if (!signer) {
        return ResponseUtils.badRequest(res, 'Signer address is required');
      }

      if (!signature) {
        return ResponseUtils.badRequest(res, 'Signature is required');
      }

      const result = await this.service.signMultisigTransaction(
        id,
        signer,
        signature
      );
      ResponseUtils.success(res, result, 'Multisig transaction signed successfully');
    } catch (error) {
      next(error);
    }
  };

  getPendingNonces = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { address, chainId } = req.params;
      const nonces = await this.service.getPendingNonces(address, Number(chainId));
      ResponseUtils.success(res, nonces);
    } catch (error) {
      next(error);
    }
  };

  optimizeGas = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, from, to, value, data } = req.body;

      if (!chainId) {
        return ResponseUtils.badRequest(res, 'Chain ID is required');
      }

      if (!from) {
        return ResponseUtils.badRequest(res, 'From address is required');
      }

      if (!to) {
        return ResponseUtils.badRequest(res, 'To address is required');
      }

      if (value === undefined) {
        return ResponseUtils.badRequest(res, 'Value is required');
      }

      const result = await this.service.optimizeGas(
        chainId,
        from,
        to,
        value,
        data
      );
      ResponseUtils.success(res, result);
    } catch (error) {
      next(error);
    }
  };
}

export const transactionBuilderController = new TransactionBuilderController();
export default transactionBuilderController;
