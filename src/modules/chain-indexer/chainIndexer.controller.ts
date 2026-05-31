import { Request, Response, NextFunction } from 'express';
import { ChainIndexerService } from './chainIndexer.service';
import { ResponseUtils } from '../../utils/response';

export class ChainIndexerController {
  private service: ChainIndexerService;

  constructor() {
    this.service = new ChainIndexerService();
  }

  indexBlock = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, blockNumber } = req.params;
      const block = await this.service.indexBlock(
        Number(chainId),
        BigInt(blockNumber)
      );
      ResponseUtils.success(res, block, 'Block indexed successfully');
    } catch (error) {
      next(error);
    }
  };

  getIndexedBlock = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, blockNumber } = req.params;
      const block = await this.service.getIndexedBlock(
        Number(chainId),
        BigInt(blockNumber)
      );
      ResponseUtils.success(res, block);
    } catch (error) {
      next(error);
    }
  };

  getIndexedBlocks = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { fromBlock, toBlock } = req.query;

      const filter = {
        chainId: Number(chainId),
        fromBlock: fromBlock ? BigInt(fromBlock as string) : undefined,
        toBlock: toBlock ? BigInt(toBlock as string) : undefined,
      };

      const result = await this.service.getIndexedBlocks(filter);
      ResponseUtils.success(res, result);
    } catch (error) {
      next(error);
    }
  };

  indexBlocksRange = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { fromBlock, toBlock } = req.body;

      if (!fromBlock || !toBlock) {
        return ResponseUtils.badRequest(res, 'fromBlock and toBlock are required');
      }

      const count = await this.service.indexBlocksRange(
        Number(chainId),
        BigInt(fromBlock),
        BigInt(toBlock)
      );
      ResponseUtils.success(res, { indexed: count }, 'Blocks indexed successfully');
    } catch (error) {
      next(error);
    }
  };

  getLatestIndexedBlock = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const block = await this.service.getLatestIndexedBlock(Number(chainId));
      
      if (!block) {
        return ResponseUtils.notFound(res, 'No indexed blocks found');
      }

      ResponseUtils.success(res, block);
    } catch (error) {
      next(error);
    }
  };

  searchTransactions = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const {
        fromAddress,
        toAddress,
        contractAddress,
        fromBlock,
        toBlock,
        page = 1,
        pageSize = 20,
      } = req.query;

      const result = await this.service.searchTransactions(
        Number(chainId),
        {
          fromAddress: fromAddress as string,
          toAddress: toAddress as string,
          contractAddress: contractAddress as string,
          fromBlock: fromBlock ? BigInt(fromBlock as string) : undefined,
          toBlock: toBlock ? BigInt(toBlock as string) : undefined,
          page: Number(page),
          pageSize: Number(pageSize),
        }
      );

      ResponseUtils.paginated(
        res,
        result.transactions,
        result.total,
        Number(page),
        Number(pageSize)
      );
    } catch (error) {
      next(error);
    }
  };

  getTransactionByHash = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, hash } = req.params;
      const transaction = await this.service.getTransactionByHash(
        Number(chainId),
        hash
      );

      if (!transaction) {
        return ResponseUtils.notFound(res, 'Transaction not found');
      }

      ResponseUtils.success(res, transaction);
    } catch (error) {
      next(error);
    }
  };

  getContractTransactions = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, contractAddress } = req.params;
      const { fromBlock, toBlock, page = 1, pageSize = 20 } = req.query;

      const result = await this.service.getContractTransactions(
        Number(chainId),
        contractAddress,
        fromBlock ? BigInt(fromBlock as string) : undefined,
        toBlock ? BigInt(toBlock as string) : undefined,
        Number(page),
        Number(pageSize)
      );

      ResponseUtils.paginated(
        res,
        result.transactions,
        result.total,
        Number(page),
        Number(pageSize)
      );
    } catch (error) {
      next(error);
    }
  };

  getAddressTransactions = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, address } = req.params;
      const { fromBlock, toBlock, page = 1, pageSize = 20 } = req.query;

      const result = await this.service.getAddressTransactions(
        Number(chainId),
        address,
        fromBlock ? BigInt(fromBlock as string) : undefined,
        toBlock ? BigInt(toBlock as string) : undefined,
        Number(page),
        Number(pageSize)
      );

      ResponseUtils.paginated(
        res,
        result.transactions,
        result.total,
        Number(page),
        Number(pageSize)
      );
    } catch (error) {
      next(error);
    }
  };

  getBlockRange = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId } = req.params;
      const { limit = 20 } = req.query;
      const blocks = await this.service.getBlockRange(
        Number(chainId),
        Number(limit)
      );
      ResponseUtils.success(res, blocks);
    } catch (error) {
      next(error);
    }
  };

  deleteBlockIndex = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, blockNumber } = req.params;
      await this.service.deleteBlockIndex(
        Number(chainId),
        BigInt(blockNumber)
      );
      ResponseUtils.success(res, { success: true }, 'Block index deleted');
    } catch (error) {
      next(error);
    }
  };
}

export const chainIndexerController = new ChainIndexerController();
export default chainIndexerController;
