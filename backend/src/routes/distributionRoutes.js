const express = require('express');
const router = express.Router();
const distributionController = require('../controllers/distributionController');

router.get('/channels', distributionController.listChannels);

router.post('/channels', distributionController.createChannel);

router.get('/channels/stats', distributionController.getDistributionStats);

router.post('/batch-distribute', distributionController.batchDistribute);

router.get('/channels/:config_id', distributionController.getChannel);

router.put('/channels/:config_id', distributionController.updateChannel);

router.delete('/channels/:config_id', distributionController.deleteChannel);

router.get('/tasks', distributionController.listDistributionTasks);

router.post('/tasks', distributionController.createDistributionTask);

router.get('/tasks/:task_id', distributionController.getDistributionTask);

router.post('/tasks/:task_id/execute', distributionController.executeDistribution);

module.exports = router;
