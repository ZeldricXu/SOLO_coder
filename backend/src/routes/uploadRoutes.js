const express = require('express');
const router = express.Router();
const multer = require('multer');
const uploadController = require('../controllers/uploadController');

const storage = multer.memoryStorage();
const upload = multer({ storage: storage });

router.post('/session', uploadController.createSession);

router.get('/session/:file_id', uploadController.getSessionStatus);

router.post('/chunk', upload.single('chunk_data'), uploadController.uploadChunk);

router.post('/complete', uploadController.completeUpload);

router.delete('/:file_id', uploadController.cancelUpload);

router.get('/status/:file_id', uploadController.getChunkStatus);

module.exports = router;
