import { Router } from 'express';
import { StorageAdapterController } from './storageAdapter.controller';

const router = Router();
const controller = new StorageAdapterController();

router.post('/upload', controller.upload);
router.get('/', controller.listItems);
router.get('/:cid', controller.getMetadata);
router.get('/:cid/download', controller.download);
router.post('/:cid/pin', controller.pin);
router.post('/:cid/unpin', controller.unpin);
router.delete('/:cid', controller.deleteItem);

export const storageAdapterRoutes = router;
export default storageAdapterRoutes;
