const express = require('express');
const router = express.Router();
const reviewController = require('../controllers/reviewController');

router.post('/', reviewController.createReview);

router.get('/stats', reviewController.getReviewStats);

router.get('/pending', reviewController.listPendingReviews);

router.get('/my', reviewController.listMyReviews);

router.get('/media/:media_id', reviewController.getReviewsByMedia);

router.get('/:review_id', reviewController.getReview);

router.post('/:review_id/start', reviewController.startReview);

router.post('/:review_id/approve', reviewController.approveReview);

router.post('/:review_id/reject', reviewController.rejectReview);

router.post('/:review_id/comment', reviewController.addComment);

router.put('/:review_id/priority', reviewController.updatePriority);

router.put('/:review_id/reassign', reviewController.reassignReview);

module.exports = router;
