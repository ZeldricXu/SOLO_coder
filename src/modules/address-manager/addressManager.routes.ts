import { Router } from 'express';
import { AddressManagerController } from './addressManager.controller';

const router = Router();
const controller = new AddressManagerController();

router.post('/', controller.createAddress);
router.get('/', controller.getAddresses);
router.get('/by-address/:chainId/:address', controller.getByAddress);
router.get('/by-tag/:tag', controller.listByTag);
router.get('/:id', controller.getAddress);
router.put('/:id', controller.updateAddress);
router.post('/:id/tags', controller.addTag);
router.delete('/:id/tags/:tag', controller.removeTag);

export const addressManagerRoutes = router;
export default addressManagerRoutes;
