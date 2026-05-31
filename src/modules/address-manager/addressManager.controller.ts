import { Request, Response, NextFunction } from 'express';
import { AddressManagerService } from './addressManager.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';

export class AddressManagerController {
  private service: AddressManagerService;

  constructor() {
    this.service = new AddressManagerService();
  }

  createAddress = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.address, req.body);
      const address = await this.service.createAddress(data);
      ResponseUtils.created(res, address, 'Address created successfully');
    } catch (error) {
      next(error);
    }
  };

  getAddress = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const address = await this.service.getAddress(id);
      ResponseUtils.success(res, address);
    } catch (error) {
      next(error);
    }
  };

  getAddresses = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const filters = {
        chainId: req.query.chainId ? Number(req.query.chainId) : undefined,
        label: req.query.label as string,
        isActive: req.query.isActive !== undefined ? req.query.isActive === 'true' : undefined,
        page: Number(req.query.page) || 1,
        pageSize: Number(req.query.pageSize) || 20,
      };

      const result = await this.service.getAddresses(filters);
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

  updateAddress = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const data = validate(validationSchemas.addressUpdate, req.body);
      const address = await this.service.updateAddress(id, data);
      ResponseUtils.success(res, address, 'Address updated successfully');
    } catch (error) {
      next(error);
    }
  };

  addTag = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { tag } = req.body;

      if (!tag || typeof tag !== 'string') {
        return ResponseUtils.badRequest(res, 'Tag is required');
      }

      const address = await this.service.addTag(id, tag);
      ResponseUtils.success(res, address, 'Tag added successfully');
    } catch (error) {
      next(error);
    }
  };

  removeTag = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id, tag } = req.params;
      const address = await this.service.removeTag(id, decodeURIComponent(tag));
      ResponseUtils.success(res, address, 'Tag removed successfully');
    } catch (error) {
      next(error);
    }
  };

  getByAddress = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { chainId, address } = req.params;
      const result = await this.service.getByAddress(Number(chainId), address);
      ResponseUtils.success(res, result);
    } catch (error) {
      next(error);
    }
  };

  listByTag = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { tag } = req.params;
      const chainId = req.query.chainId ? Number(req.query.chainId) : undefined;
      const addresses = await this.service.listByTag(decodeURIComponent(tag), chainId);
      ResponseUtils.success(res, addresses);
    } catch (error) {
      next(error);
    }
  };
}

export const addressManagerController = new AddressManagerController();
export default addressManagerController;
