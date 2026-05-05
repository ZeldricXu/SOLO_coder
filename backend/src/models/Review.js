const mongoose = require('mongoose');
const { Schema } = mongoose;

const commentSchema = new Schema({
  reviewer_id: {
    type: String,
    required: true
  },
  comment: {
    type: String,
    required: true,
    trim: true
  },
  created_at: {
    type: Date,
    default: Date.now
  }
}, { _id: false });

const reviewSchema = new Schema({
  review_id: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  media_id: {
    type: String,
    required: true,
    index: true,
    ref: 'Media'
  },
  reviewer_id: {
    type: String,
    required: true
  },
  status: {
    type: String,
    required: true,
    enum: ['pending', 'in_progress', 'approved', 'rejected'],
    default: 'pending'
  },
  priority: {
    type: String,
    required: true,
    enum: ['low', 'medium', 'high', 'urgent'],
    default: 'medium'
  },
  comments: {
    type: [commentSchema],
    default: []
  },
  assigned_at: {
    type: Date,
    default: Date.now
  },
  completed_at: {
    type: Date
  },
  created_at: {
    type: Date,
    default: Date.now,
    index: true
  },
  updated_at: {
    type: Date,
    default: Date.now
  }
}, {
  timestamps: { createdAt: 'created_at', updatedAt: 'updated_at' },
  collection: 'review_tasks'
});

reviewSchema.index({ status: 1, priority: 1, created_at: 1 });

reviewSchema.pre('save', function(next) {
  this.updated_at = Date.now();
  next();
});

reviewSchema.statics.findByReviewId = function(reviewId) {
  return this.findOne({ review_id: reviewId });
};

reviewSchema.statics.findByMediaId = function(mediaId) {
  return this.findOne({ media_id: mediaId }).sort({ created_at: -1 });
};

reviewSchema.statics.listByReviewer = function(reviewerId, status, page = 1, limit = 20) {
  const skip = (page - 1) * limit;
  const filter = { reviewer_id: reviewerId };
  
  if (status) {
    filter.status = status;
  }
  
  return this.find(filter)
    .populate('media_id')
    .sort({ priority: -1, created_at: -1 })
    .skip(skip)
    .limit(limit);
};

reviewSchema.statics.listPending = function(page = 1, limit = 20) {
  const skip = (page - 1) * limit;
  return this.find({ status: 'pending' })
    .populate('media_id')
    .sort({ priority: -1, created_at: 1 })
    .skip(skip)
    .limit(limit);
};

reviewSchema.statics.createReview = function(mediaId, reviewerId, priority = 'medium') {
  const review = new this({
    review_id: `review_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    media_id: mediaId,
    reviewer_id: reviewerId,
    status: 'pending',
    priority: priority
  });
  return review.save();
};

reviewSchema.methods.approve = function(reviewerId, comment = '') {
  this.status = 'approved';
  this.completed_at = Date.now();
  
  if (comment) {
    this.comments.push({
      reviewer_id: reviewerId,
      comment: comment,
      created_at: Date.now()
    });
  }
  
  return this.save();
};

reviewSchema.methods.reject = function(reviewerId, reason = '') {
  this.status = 'rejected';
  this.completed_at = Date.now();
  
  if (reason) {
    this.comments.push({
      reviewer_id: reviewerId,
      comment: reason,
      created_at: Date.now()
    });
  }
  
  return this.save();
};

reviewSchema.methods.addComment = function(reviewerId, comment) {
  this.comments.push({
    reviewer_id: reviewerId,
    comment: comment,
    created_at: Date.now()
  });
  return this.save();
};

module.exports = mongoose.model('Review', reviewSchema);
