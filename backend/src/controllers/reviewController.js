const reviewService = require('../services/reviewService');
const storageService = require('../services/storageService');

class ReviewController {
  async createReview(req, res) {
    try {
      const { media_id, priority } = req.body;
      const reviewer_id = req.user?.id || 'system';
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const result = await reviewService.createReview(
        media_id,
        reviewer_id,
        priority || 'medium'
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in createReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getReview(req, res) {
    try {
      const { review_id } = req.params;
      
      if (!review_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing review_id parameter'
        });
      }

      const result = await reviewService.getReview(review_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      const review = result.review;
      if (review.media?.storage_path) {
        try {
          review.media.presigned_url = await storageService.getPresignedUrl(
            review.media.storage_path,
            3600
          );
        } catch (urlError) {
          console.warn('Error generating presigned URL:', urlError);
        }
      }

      return res.status(200).json({
        code: 200,
        data: review
      });
    } catch (error) {
      console.error('Error in getReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getReviewsByMedia(req, res) {
    try {
      const { media_id } = req.params;
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const result = await reviewService.getReviewsByMedia(media_id);

      return res.status(200).json({
        code: 200,
        data: result.reviews
      });
    } catch (error) {
      console.error('Error in getReviewsByMedia:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async listPendingReviews(req, res) {
    try {
      const { page = 1, limit = 20 } = req.query;
      
      const pageNum = parseInt(page) || 1;
      const limitNum = parseInt(limit) || 20;

      const result = await reviewService.listPendingReviews(pageNum, limitNum);

      for (const review of result.reviews) {
        if (review.media_id?.storage_path) {
          try {
            review.media_id.presigned_url = await storageService.getPresignedUrl(
              review.media_id.storage_path,
              3600
            );
          } catch (urlError) {
            console.warn('Error generating presigned URL:', urlError);
          }
        }
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in listPendingReviews:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async listMyReviews(req, res) {
    try {
      const reviewer_id = req.user?.id || 'system';
      const { status, page = 1, limit = 20 } = req.query;
      
      const pageNum = parseInt(page) || 1;
      const limitNum = parseInt(limit) || 20;

      const result = await reviewService.listReviewsByReviewer(
        reviewer_id,
        status,
        pageNum,
        limitNum
      );

      for (const review of result.reviews) {
        if (review.media_id?.storage_path) {
          try {
            review.media_id.presigned_url = await storageService.getPresignedUrl(
              review.media_id.storage_path,
              3600
            );
          } catch (urlError) {
            console.warn('Error generating presigned URL:', urlError);
          }
        }
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in listMyReviews:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async startReview(req, res) {
    try {
      const { review_id } = req.params;
      const reviewer_id = req.user?.id || 'system';
      
      if (!review_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing review_id parameter'
        });
      }

      const result = await reviewService.startReview(review_id, reviewer_id);

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in startReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async approveReview(req, res) {
    try {
      const { review_id } = req.params;
      const { comment } = req.body;
      const reviewer_id = req.user?.id || 'system';
      
      if (!review_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing review_id parameter'
        });
      }

      const result = await reviewService.approveReview(
        review_id,
        reviewer_id,
        comment || ''
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in approveReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async rejectReview(req, res) {
    try {
      const { review_id } = req.params;
      const { reason } = req.body;
      const reviewer_id = req.user?.id || 'system';
      
      if (!review_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing review_id parameter'
        });
      }

      const result = await reviewService.rejectReview(
        review_id,
        reviewer_id,
        reason || ''
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in rejectReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async addComment(req, res) {
    try {
      const { review_id } = req.params;
      const { comment } = req.body;
      const reviewer_id = req.user?.id || 'system';
      
      if (!review_id || !comment) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required parameters: review_id and comment'
        });
      }

      const result = await reviewService.addComment(
        review_id,
        reviewer_id,
        comment
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in addComment:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async updatePriority(req, res) {
    try {
      const { review_id } = req.params;
      const { priority } = req.body;
      
      if (!review_id || !priority) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required parameters: review_id and priority'
        });
      }

      const result = await reviewService.updatePriority(review_id, priority);

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in updatePriority:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async reassignReview(req, res) {
    try {
      const { review_id } = req.params;
      const { new_reviewer_id } = req.body;
      
      if (!review_id || !new_reviewer_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required parameters: review_id and new_reviewer_id'
        });
      }

      const result = await reviewService.reassignReview(review_id, new_reviewer_id);

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in reassignReview:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getReviewStats(req, res) {
    try {
      const reviewer_id = req.user?.id;
      const result = await reviewService.getReviewStats(reviewer_id);

      return res.status(200).json({
        code: 200,
        data: result.stats
      });
    } catch (error) {
      console.error('Error in getReviewStats:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }
}

module.exports = new ReviewController();
