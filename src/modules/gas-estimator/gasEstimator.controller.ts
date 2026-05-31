import { Request, Response, NextFunction } from 'express';
import { GasEstimatorService } from './gasEstimator.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';

export class GasEstimatorController {
  private service: GasEstimatorService;

  constructor() {
    this.service = new GasEstimatorService();
  }

  getGasEstimate = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const estimate = await this.service.getGasEstimate(Number(chainId));
      ResponseUtils.success(res, estimate);
    } catch (error) {
      next(error);
    }
  };

  estimateTransactionGas = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.gasEstimate, req.body);
      const result = await this.service.estimateTransactionGas(data);
      ResponseUtils.success(res, result);
    } catch (error) {
      next(error);
    }
  };

  getGasHistory = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { fromTime, toTime, limit } = req.query;

      const from = fromTime ? new Date(fromTime as string) : undefined;
      const to = toTime ? new Date(toTime as string) : undefined;
      const limitNum = limit ? Number(limit) : 100;

      const history = await this.service.getGasHistory(
        Number(chainId),
        from,
        to,
        limitNum
      );
      ResponseUtils.success(res, history);
    } catch (error) {
      next(error);
    }
  };

  getGasStatistics = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const hours = req.query.hours ? Number(req.query.hours) : 24;
      const stats = await this.service.getGasStatistics(Number(chainId), hours);
      ResponseUtils.success(res, stats);
    } catch (error) {
      next(error);
    }
  };

  recordGasPrice = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { blockNumber } = req.body;

      if (!blockNumber) {
        return ResponseUtils.badRequest(res, 'Block number is required');
      }

      await this.service.recordGasPrice(
        Number(chainId),
        BigInt(blockNumber)
      );
      ResponseUtils.success(res, { success: true }, 'Gas price recorded successfully');
    } catch (error) {
      next(error);
    }
  };
}

export const gasEstimatorController = new GasEstimatorController();
export default gasEstimatorController;
