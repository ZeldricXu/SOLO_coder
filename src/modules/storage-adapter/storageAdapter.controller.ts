import { Request, Response, NextFunction } from 'express';
import { StorageAdapterService } from './storageAdapter.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';

export class StorageAdapterController {
  private service: StorageAdapterService;

  constructor() {
    this.service = new StorageAdapterService();
  }

  upload = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const contentType = req.headers['content-type'] || 'application/json';
      const storageNetwork = (req.body.storageNetwork || req.query.storageNetwork || 'ipfs') as 'ipfs' | 'arweave' | 'arweave-bundlr';
      const pin = req.body.pin !== false && req.query.pin !== 'false';
      const metadata = req.body.metadata || {};

      let data: Buffer | string;
      let actualContentType: string;

      if (Buffer.isBuffer(req.body)) {
        data = req.body;
        actualContentType = contentType;
      } else if (typeof req.body.data === 'string') {
        if (req.body.data.startsWith('data:')) {
          const match = req.body.data.match(/^data:([^;]+);base64,(.+)$/);
          if (match) {
            actualContentType = match[1];
            data = Buffer.from(match[2], 'base64');
          } else {
            data = req.body.data;
            actualContentType = contentType;
          }
        } else {
          data = req.body.data;
          actualContentType = contentType;
        }
      } else {
        data = JSON.stringify(req.body);
        actualContentType = 'application/json';
      }

      const result = await this.service.upload({
        data,
        contentType: actualContentType,
        storageNetwork,
        pin,
        metadata,
      });

      ResponseUtils.created(res, result, 'Upload successful');
    } catch (error) {
      next(error);
    }
  };

  download = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { cid } = req.params;
      const storageNetwork = (req.query.storageNetwork || 'ipfs') as 'ipfs' | 'arweave' | 'arweave-bundlr';

      const result = await this.service.download({ cid, storageNetwork });

      res.setHeader('Content-Type', result.contentType);
      res.setHeader('Content-Disposition', `attachment; filename="${cid}"`);
      res.send(result.data);
    } catch (error) {
      next(error);
    }
  };

  getMetadata = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { cid } = req.params;
      const item = await this.service.getStorageItem(cid);
      ResponseUtils.success(res, item);
    } catch (error) {
      next(error);
    }
  };

  listItems = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const filters = {
        storageNetwork: req.query.storageNetwork as string,
        isPinned: req.query.isPinned !== undefined ? req.query.isPinned === 'true' : undefined,
        page: Number(req.query.page) || 1,
        pageSize: Number(req.query.pageSize) || 20,
      };

      const result = await this.service.listStorageItems(filters);
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

  pin = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { cid } = req.params;
      const storageNetwork = (req.query.storageNetwork || 'ipfs') as 'ipfs' | 'arweave' | 'arweave-bundlr';
      const result = await this.service.pin({ cid, storageNetwork });
      ResponseUtils.success(res, result, 'Pin successful');
    } catch (error) {
      next(error);
    }
  };

  unpin = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { cid } = req.params;
      const storageNetwork = (req.query.storageNetwork || 'ipfs') as 'ipfs' | 'arweave' | 'arweave-bundlr';
      const result = await this.service.unpin({ cid, storageNetwork });
      ResponseUtils.success(res, result, 'Unpin successful');
    } catch (error) {
      next(error);
    }
  };

  deleteItem = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { cid } = req.params;
      const result = await this.service.deleteStorageItem(cid);
      ResponseUtils.success(res, result, 'Delete successful');
    } catch (error) {
      next(error);
    }
  };
}

export const storageAdapterController = new StorageAdapterController();
export default storageAdapterController;
