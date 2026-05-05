const express = require('express');
const router = express.Router();
const mediaController = require('../controllers/mediaController');

router.get('/', mediaController.getMediaList);

router.get('/stats', mediaController.getMediaStats);

router.post('/batch-delete', mediaController.batchDelete);

router.get('/:media_id', mediaController.getMediaById);

router.put('/:media_id', mediaController.updateMedia);

router.delete('/:media_id', mediaController.deleteMedia);

router.get('/:media_id/presigned-url', mediaController.getPresignedUrl);

module.exports = router;
