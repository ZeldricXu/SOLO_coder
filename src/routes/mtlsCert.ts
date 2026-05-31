import { Router, Request, Response } from 'express';
import { asyncHandler } from '../middleware/errorHandler';
import * as service from '../modules/mtlsCert/service';
import { CertificateSchema, CertificateSigningRequestSchema, RevokeCertificateSchema } from '../modules/mtlsCert/types';

const router = Router();

const getPaginationParams = (req: Request) => ({
  page: parseInt(req.query.page as string) || 1,
  pageSize: parseInt(req.query.pageSize as string) || 20,
});

router.post('/certificates', asyncHandler(async (req: Request, res: Response) => {
  const data = CertificateSchema.parse(req.body);
  const result = await service.createCertificate(data);
  res.status(201).json({ code: 201, data: result });
}));

router.post('/certificates/csr', asyncHandler(async (req: Request, res: Response) => {
  const data = CertificateSigningRequestSchema.parse(req.body);
  const result = await service.createCSR(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/certificates', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const status = req.query.status as string | undefined;
  const commonName = req.query.commonName as string | undefined;
  const result = await service.listCertificates(params, status as never, commonName);
  res.json({ code: 200, data: result });
}));

router.get('/certificates/expiring', asyncHandler(async (req: Request, res: Response) => {
  const days = parseInt(req.query.days as string) || 30;
  const result = await service.getExpiringCertificates(days);
  res.json({ code: 200, data: result });
}));

router.get('/certificates/:certId', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.getCertificate(req.params.certId);
  res.json({ code: 200, data: result });
}));

router.post('/certificates/import', asyncHandler(async (req: Request, res: Response) => {
  const { certificate, privateKey } = req.body as { certificate: string; privateKey: string };
  const result = await service.importCertificate(certificate, privateKey);
  res.status(201).json({ code: 201, data: result });
}));

router.post('/certificates/:certId/renew', asyncHandler(async (req: Request, res: Response) => {
  const result = await service.renewCertificate(req.params.certId);
  res.json({ code: 200, data: result });
}));

router.delete('/certificates/:certId', asyncHandler(async (req: Request, res: Response) => {
  await service.deleteCertificate(req.params.certId);
  res.json({ code: 200, message: 'Certificate deleted' });
}));

router.post('/revocations', asyncHandler(async (req: Request, res: Response) => {
  const data = RevokeCertificateSchema.parse(req.body);
  const result = await service.revokeCertificate(data);
  res.status(201).json({ code: 201, data: result });
}));

router.get('/revocations', asyncHandler(async (req: Request, res: Response) => {
  const params = getPaginationParams(req);
  const result = await service.listRevocations(params);
  res.json({ code: 200, data: result });
}));

router.get('/crl', asyncHandler(async (_req: Request, res: Response) => {
  const result = await service.getCRL();
  res.json({ code: 200, data: result });
}));

router.get('/rotation-policy', asyncHandler(async (_req: Request, res: Response) => {
  const result = service.getRotationPolicy();
  res.json({ code: 200, data: result });
}));

router.put('/rotation-policy', asyncHandler(async (req: Request, res: Response) => {
  const result = service.updateRotationPolicy(req.body);
  res.json({ code: 200, data: result });
}));

export default router;
