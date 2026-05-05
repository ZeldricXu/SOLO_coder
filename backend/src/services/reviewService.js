const Review = require('../models/Review');
const Media = require('../models/Media');

class ReviewService {
  async createReview(mediaId, reviewerId, priority = 'medium') {
    try {
      const media = await Media.findByMediaId(mediaId);
      if (!media) {
        return {
          success: false,
          message: 'Media not found'
        };
      }

      const existingReview = await Review.findByMediaId(mediaId);
      if (existingReview && ['pending', 'in_progress'].includes(existingReview.status)) {
        return {
          success: false,
          message: 'Active review already exists for this media'
        };
      }

      const review = await Review.createReview(mediaId, reviewerId, priority);
      
      media.status = 'pending_review';
      await media.save();

      return {
        success: true,
        review_id: review.review_id,
        media_id: mediaId,
        status: review.status,
        priority: review.priority
      };
    } catch (error) {
      console.error('Error creating review:', error);
      return {
        success: false,
        message: 'Failed to create review',
        error: error.message
      };
    }
  }

  async getReview(reviewId) {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      const media = await Media.findByMediaId(review.media_id);

      return {
        success: true,
        review: {
          ...review.toObject(),
          media: media ? media.toObject() : null
        }
      };
    } catch (error) {
      console.error('Error getting review:', error);
      return {
        success: false,
        message: 'Failed to get review',
        error: error.message
      };
    }
  }

  async getReviewsByMedia(mediaId) {
    try {
      const review = await Review.findByMediaId(mediaId);
      
      if (!review) {
        return {
          success: true,
          reviews: []
        };
      }

      return {
        success: true,
        reviews: [review.toObject()]
      };
    } catch (error) {
      console.error('Error getting reviews by media:', error);
      return {
        success: false,
        message: 'Failed to get reviews',
        error: error.message
      };
    }
  }

  async listPendingReviews(page = 1, limit = 20) {
    try {
      const reviews = await Review.listPending(page, limit);
      
      return {
        success: true,
        reviews: reviews.map(r => r.toObject()),
        pagination: {
          page,
          limit
        }
      };
    } catch (error) {
      console.error('Error listing pending reviews:', error);
      return {
        success: false,
        message: 'Failed to list pending reviews',
        error: error.message
      };
    }
  }

  async listReviewsByReviewer(reviewerId, status = null, page = 1, limit = 20) {
    try {
      const reviews = await Review.listByReviewer(reviewerId, status, page, limit);
      
      return {
        success: true,
        reviews: reviews.map(r => r.toObject()),
        pagination: {
          page,
          limit
        }
      };
    } catch (error) {
      console.error('Error listing reviews by reviewer:', error);
      return {
        success: false,
        message: 'Failed to list reviews',
        error: error.message
      };
    }
  }

  async startReview(reviewId, reviewerId) {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      if (review.status !== 'pending') {
        return {
          success: false,
          message: 'Review is not in pending status'
        };
      }

      review.status = 'in_progress';
      review.reviewer_id = reviewerId;
      await review.save();

      return {
        success: true,
        review_id: review.review_id,
        status: review.status,
        reviewer_id: review.reviewer_id
      };
    } catch (error) {
      console.error('Error starting review:', error);
      return {
        success: false,
        message: 'Failed to start review',
        error: error.message
      };
    }
  }

  async approveReview(reviewId, reviewerId, comment = '') {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      if (!['pending', 'in_progress'].includes(review.status)) {
        return {
          success: false,
          message: 'Review cannot be approved in current status'
        };
      }

      await review.approve(reviewerId, comment);

      const media = await Media.findByMediaId(review.media_id);
      if (media) {
        media.status = 'approved';
        media.reviewed_at = Date.now();
        await media.save();
      }

      return {
        success: true,
        review_id: review.review_id,
        status: review.status,
        completed_at: review.completed_at
      };
    } catch (error) {
      console.error('Error approving review:', error);
      return {
        success: false,
        message: 'Failed to approve review',
        error: error.message
      };
    }
  }

  async rejectReview(reviewId, reviewerId, reason = '') {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      if (!['pending', 'in_progress'].includes(review.status)) {
        return {
          success: false,
          message: 'Review cannot be rejected in current status'
        };
      }

      await review.reject(reviewerId, reason);

      const media = await Media.findByMediaId(review.media_id);
      if (media) {
        media.status = 'rejected';
        media.reviewed_at = Date.now();
        await media.save();
      }

      return {
        success: true,
        review_id: review.review_id,
        status: review.status,
        completed_at: review.completed_at
      };
    } catch (error) {
      console.error('Error rejecting review:', error);
      return {
        success: false,
        message: 'Failed to reject review',
        error: error.message
      };
    }
  }

  async addComment(reviewId, reviewerId, comment) {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      if (!comment || comment.trim().length === 0) {
        return {
          success: false,
          message: 'Comment cannot be empty'
        };
      }

      await review.addComment(reviewerId, comment.trim());

      return {
        success: true,
        review_id: review.review_id,
        comments: review.comments
      };
    } catch (error) {
      console.error('Error adding comment:', error);
      return {
        success: false,
        message: 'Failed to add comment',
        error: error.message
      };
    }
  }

  async updatePriority(reviewId, priority) {
    try {
      const validPriorities = ['low', 'medium', 'high', 'urgent'];
      if (!validPriorities.includes(priority)) {
        return {
          success: false,
          message: 'Invalid priority value'
        };
      }

      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      review.priority = priority;
      await review.save();

      return {
        success: true,
        review_id: review.review_id,
        priority: review.priority
      };
    } catch (error) {
      console.error('Error updating priority:', error);
      return {
        success: false,
        message: 'Failed to update priority',
        error: error.message
      };
    }
  }

  async reassignReview(reviewId, newReviewerId) {
    try {
      const review = await Review.findByReviewId(reviewId);
      
      if (!review) {
        return {
          success: false,
          message: 'Review not found'
        };
      }

      if (review.status === 'approved' || review.status === 'rejected') {
        return {
          success: false,
          message: 'Cannot reassign completed review'
        };
      }

      review.reviewer_id = newReviewerId;
      review.status = 'pending';
      await review.save();

      return {
        success: true,
        review_id: review.review_id,
        reviewer_id: review.reviewer_id,
        status: review.status
      };
    } catch (error) {
      console.error('Error reassigning review:', error);
      return {
        success: false,
        message: 'Failed to reassign review',
        error: error.message
      };
    }
  }

  async getReviewStats(reviewerId = null) {
    try {
      const filter = {};
      if (reviewerId) {
        filter.reviewer_id = reviewerId;
      }

      const total = await Review.countDocuments(filter);
      const pending = await Review.countDocuments({ ...filter, status: 'pending' });
      const inProgress = await Review.countDocuments({ ...filter, status: 'in_progress' });
      const approved = await Review.countDocuments({ ...filter, status: 'approved' });
      const rejected = await Review.countDocuments({ ...filter, status: 'rejected' });

      return {
        success: true,
        stats: {
          total,
          pending,
          in_progress: inProgress,
          approved,
          rejected
        }
      };
    } catch (error) {
      console.error('Error getting review stats:', error);
      return {
        success: false,
        message: 'Failed to get review stats',
        error: error.message
      };
    }
  }
}

module.exports = new ReviewService();
