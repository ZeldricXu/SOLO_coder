const Comment = require('../models/Comment');
const Document = require('../models/Document');
const { v4: uuidv4 } = require('uuid');
const _ = require('lodash');

class CommentService {
  async createComment(doc_id, user_id, content, position = null, parent_comment_id = null) {
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      throw new Error(`Document not found: ${doc_id}`);
    }
    
    const comment = new Comment({
      comment_id: uuidv4(),
      doc_id,
      user_id,
      content,
      position,
      parent_comment_id,
      is_resolved: false
    });
    
    await comment.save();
    
    console.log(`Created comment ${comment.comment_id} for document ${doc_id}`);
    return comment.toObject();
  }

  async getComments(doc_id, include_resolved = false) {
    const query = { doc_id };
    
    if (!include_resolved) {
      query.is_resolved = false;
    }
    
    const comments = await Comment.find(query)
      .sort({ created_at: 1 })
      .exec();
    
    return this.buildCommentTree(comments);
  }

  buildCommentTree(comments) {
    const commentMap = new Map();
    const rootComments = [];
    
    for (const comment of comments) {
      const commentObj = {
        ...comment.toObject(),
        replies: []
      };
      commentMap.set(comment.comment_id, commentObj);
    }
    
    for (const comment of commentMap.values()) {
      if (comment.parent_comment_id) {
        const parent = commentMap.get(comment.parent_comment_id);
        if (parent) {
          parent.replies.push(comment);
        }
      } else {
        rootComments.push(comment);
      }
    }
    
    return rootComments;
  }

  async getComment(comment_id) {
    const comment = await Comment.findOne({ comment_id });
    
    if (!comment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    return comment.toObject();
  }

  async updateComment(comment_id, user_id, updates) {
    const comment = await Comment.findOne({ comment_id });
    
    if (!comment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    if (comment.user_id !== user_id) {
      throw new Error('Not authorized to update this comment');
    }
    
    const allowedUpdates = ['content', 'position'];
    const filteredUpdates = _.pick(updates, allowedUpdates);
    
    Object.assign(comment, filteredUpdates);
    await comment.save();
    
    console.log(`Updated comment ${comment_id}`);
    return comment.toObject();
  }

  async deleteComment(comment_id, user_id) {
    const comment = await Comment.findOne({ comment_id });
    
    if (!comment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    if (comment.user_id !== user_id) {
      throw new Error('Not authorized to delete this comment');
    }
    
    await Comment.deleteMany({ parent_comment_id: comment_id });
    await Comment.deleteOne({ comment_id });
    
    console.log(`Deleted comment ${comment_id}`);
    return { deleted: true, comment_id };
  }

  async resolveComment(comment_id, resolved_by) {
    const comment = await Comment.findOne({ comment_id });
    
    if (!comment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    comment.is_resolved = true;
    comment.resolved_by = resolved_by;
    comment.resolved_at = new Date();
    
    await comment.save();
    
    console.log(`Resolved comment ${comment_id} by ${resolved_by}`);
    return comment.toObject();
  }

  async unresolveComment(comment_id, user_id) {
    const comment = await Comment.findOne({ comment_id });
    
    if (!comment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    comment.is_resolved = false;
    comment.resolved_by = undefined;
    comment.resolved_at = undefined;
    
    await comment.save();
    
    console.log(`Unresolved comment ${comment_id} by ${user_id}`);
    return comment.toObject();
  }

  async addReply(comment_id, user_id, content) {
    const parentComment = await Comment.findOne({ comment_id });
    
    if (!parentComment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    const reply = new Comment({
      comment_id: uuidv4(),
      doc_id: parentComment.doc_id,
      user_id,
      content,
      parent_comment_id: comment_id,
      is_resolved: false
    });
    
    await reply.save();
    
    console.log(`Created reply ${reply.comment_id} to comment ${comment_id}`);
    return reply.toObject();
  }

  async getCommentThread(comment_id) {
    const rootComment = await Comment.findOne({ comment_id });
    
    if (!rootComment) {
      throw new Error(`Comment not found: ${comment_id}`);
    }
    
    const allComments = await Comment.find({ doc_id: rootComment.doc_id })
      .sort({ created_at: 1 })
      .exec();
    
    const threadComments = [];
    const queue = [comment_id];
    
    while (queue.length > 0) {
      const currentId = queue.shift();
      const comment = allComments.find(c => c.comment_id === currentId);
      
      if (comment) {
        threadComments.push(comment);
        
        const replies = allComments.filter(c => c.parent_comment_id === currentId);
        for (const reply of replies) {
          queue.push(reply.comment_id);
        }
      }
    }
    
    return this.buildCommentTree(threadComments);
  }

  async getCommentsByPosition(doc_id, start_offset, end_offset) {
    const comments = await Comment.find({
      doc_id,
      is_resolved: false,
      $or: [
        { 'position.start_offset': { $lte: end_offset }, 'position.end_offset': { $gte: start_offset } },
        { position: { $exists: false } }
      ]
    })
    .sort({ created_at: 1 })
    .exec();
    
    return comments.map(c => c.toObject());
  }

  async getCommentStats(doc_id) {
    const [total, resolved, unresolved] = await Promise.all([
      Comment.countDocuments({ doc_id }),
      Comment.countDocuments({ doc_id, is_resolved: true }),
      Comment.countDocuments({ doc_id, is_resolved: false })
    ]);
    
    return {
      doc_id,
      total,
      resolved,
      unresolved
    };
  }

  async batchResolveComments(doc_id, comment_ids, resolved_by) {
    const result = await Comment.updateMany(
      {
        doc_id,
        comment_id: { $in: comment_ids }
      },
      {
        $set: {
          is_resolved: true,
          resolved_by,
          resolved_at: new Date()
        }
      }
    );
    
    console.log(`Batch resolved ${result.modifiedCount} comments for document ${doc_id}`);
    return {
      resolved_count: result.modifiedCount,
      comment_ids
    };
  }

  async getRecentComments(user_id = null, limit = 20) {
    const query = user_id ? { user_id } : {};
    
    const comments = await Comment.find(query)
      .sort({ created_at: -1 })
      .limit(limit)
      .populate({
        path: 'doc_id',
        select: 'doc_id title'
      })
      .exec();
    
    return comments.map(c => ({
      ...c.toObject(),
      document: c.doc_id
    }));
  }
}

module.exports = new CommentService();
