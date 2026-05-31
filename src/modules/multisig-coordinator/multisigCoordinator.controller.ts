import { Request, Response, NextFunction } from 'express';
import { MultisigCoordinatorService } from './multisigCoordinator.service';
import { ResponseUtils } from '../../utils/response';
import { validationSchemas, validate } from '../../utils/validation';
import { ProposalStatus, ProposalType } from '../../types';

export class MultisigCoordinatorController {
  private service: MultisigCoordinatorService;

  constructor() {
    this.service = new MultisigCoordinatorService();
  }

  createProposal = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const data = validate(validationSchemas.proposal, req.body);
      const proposal = await this.service.createProposal(data);
      ResponseUtils.created(res, proposal, 'Proposal created successfully');
    } catch (error) {
      next(error);
    }
  };

  signProposal = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { signer, signature } = req.body;

      if (!signer) {
        return ResponseUtils.badRequest(res, 'Signer address is required');
      }

      if (!signature) {
        return ResponseUtils.badRequest(res, 'Signature is required');
      }

      const proposal = await this.service.signProposal({
        proposalId: id,
        signer,
        signature,
      });
      ResponseUtils.success(res, proposal, 'Proposal signed successfully');
    } catch (error) {
      next(error);
    }
  };

  executeProposal = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const result = await this.service.executeProposal({ proposalId: id });
      ResponseUtils.success(res, result, 'Proposal executed successfully');
    } catch (error) {
      next(error);
    }
  };

  getProposal = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const proposal = await this.service.getProposal(id);
      ResponseUtils.success(res, proposal);
    } catch (error) {
      next(error);
    }
  };

  getProposals = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const filters = {
        walletId: req.query.walletId as string,
        chainId: req.query.chainId ? Number(req.query.chainId) : undefined,
        status: req.query.status as ProposalStatus,
        type: req.query.type as ProposalType,
        page: Number(req.query.page) || 1,
        pageSize: Number(req.query.pageSize) || 20,
      };

      const result = await this.service.getProposals(filters);
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

  getPendingProposals = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { walletId } = req.params;
      const proposals = await this.service.getPendingProposals(walletId);
      ResponseUtils.success(res, proposals);
    } catch (error) {
      next(error);
    }
  };

  getApprovedProposals = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { walletId } = req.params;
      const proposals = await this.service.getApprovedProposals(walletId);
      ResponseUtils.success(res, proposals);
    } catch (error) {
      next(error);
    }
  };

  rejectProposal = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const { reason } = req.body;
      const proposal = await this.service.rejectProposal(id, reason);
      ResponseUtils.success(res, proposal, 'Proposal rejected successfully');
    } catch (error) {
      next(error);
    }
  };

  getProposalSignatures = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const signatures = await this.service.getProposalSignatures(id);
      ResponseUtils.success(res, signatures);
    } catch (error) {
      next(error);
    }
  };

  canExecute = async (req: Request, res: Response, next: NextFunction) => {
    try {
      const { id } = req.params;
      const canExecute = await this.service.canExecute(id);
      ResponseUtils.success(res, { canExecute });
    } catch (error) {
      next(error);
    }
  };
}

export const multisigCoordinatorController = new MultisigCoordinatorController();
export default multisigCoordinatorController;
