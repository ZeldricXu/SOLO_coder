const express = require('express');
const router = express.Router();
const commentService = require('../services/CommentService');

const DEFAULT_USER_ID = 'user_default';

router.get('/doc/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { include_resolved = false } = req.query;
    
    const comments = await commentService.getComments(
      doc_id,
      include_resolved === 'true'
    );
    
    res.json({
      code: 200,
      data: comments
    });
  } catch (error) {
    console.error('Get comments error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get comments',
      error: error.message
    });
  }
});

router.get('/stats/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    
    const stats = await commentService.getCommentStats(doc_id);
    
    res.json({
      code: 200,
      data: stats
    });
  } catch (error) {
    console.error('Get comment stats error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get comment stats',
      error: error.message
    });
  }
});

router.get('/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    
    const comment = await commentService.getComment(comment_id);
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    console.error('Get comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to get comment',
      error: error.message
    });
  }
});

router.post('/', async (req, res) => {
  try {
    const { doc_id, user_id, content, position, parent_comment_id } = req.body;
    
    if (!doc_id || !content) {
      return res.status(400).json({
        code: 400,
        message: 'doc_id and content are required'
      });
    }
    
    const userId = user_id || DEFAULT_USER_ID;
    const comment = await commentService.createComment(
      doc_id,
      userId,
      content,
      position,
      parent_comment_id
    );
    
    res.status(201).json({
      code: 201,
      data: comment
    });
  } catch (error) {
    console.error('Create comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to create comment',
      error: error.message
    });
  }
});

router.put('/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { user_id, content, position } = req.body;
    
    if (!user_id) {
      return res.status(400).json({
        code: 400,
        message: 'user_id is required'
      });
    }
    
    const comment = await commentService.updateComment(
      comment_id,
      user_id,
      { content, position }
    );
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    console.error('Update comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    if (error.message.includes('Not authorized')) {
      return res.status(403).json({
        code: 403,
        message: 'Not authorized to update this comment'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to update comment',
      error: error.message
    });
  }
});

router.delete('/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { user_id } = req.query;
    
    if (!user_id) {
      return res.status(400).json({
        code: 400,
        message: 'user_id is required'
      });
    }
    
    const result = await commentService.deleteComment(comment_id, user_id);
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Delete comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    if (error.message.includes('Not authorized')) {
      return res.status(403).json({
        code: 403,
        message: 'Not authorized to delete this comment'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to delete comment',
      error: error.message
    });
  }
});

router.post('/:comment_id/resolve', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { user_id } = req.body;
    
    if (!user_id) {
      return res.status(400).json({
        code: 400,
        message: 'user_id is required'
      });
    }
    
    const comment = await commentService.resolveComment(comment_id, user_id);
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    console.error('Resolve comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to resolve comment',
      error: error.message
    });
  }
});

router.post('/:comment_id/unresolve', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { user_id } = req.body;
    
    if (!user_id) {
      return res.status(400).json({
        code: 400,
        message: 'user_id is required'
      });
    }
    
    const comment = await commentService.unresolveComment(comment_id, user_id);
    
    res.json({
      code: 200,
      data: comment
    });
  } catch (error) {
    console.error('Unresolve comment error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to unresolve comment',
      error: error.message
    });
  }
});

router.post('/:comment_id/reply', async (req, res) => {
  try {
    const { comment_id } = req.params;
    const { user_id, content } = req.body;
    
    if (!content) {
      return res.status(400).json({
        code: 400,
        message: 'content is required'
      });
    }
    
    const userId = user_id || DEFAULT_USER_ID;
    const reply = await commentService.addReply(comment_id, userId, content);
    
    res.status(201).json({
      code: 201,
      data: reply
    });
  } catch (error) {
    console.error('Add reply error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to add reply',
      error: error.message
    });
  }
});

router.get('/thread/:comment_id', async (req, res) => {
  try {
    const { comment_id } = req.params;
    
    const thread = await commentService.getCommentThread(comment_id);
    
    res.json({
      code: 200,
      data: thread
    });
  } catch (error) {
    console.error('Get comment thread error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Comment not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to get comment thread',
      error: error.message
    });
  }
});

router.get('/position/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { start_offset, end_offset } = req.query;
    
    if (start_offset === undefined || end_offset === undefined) {
      return res.status(400).json({
        code: 400,
        message: 'start_offset and end_offset are required'
      });
    }
    
    const comments = await commentService.getCommentsByPosition(
      doc_id,
      parseInt(start_offset),
      parseInt(end_offset)
    );
    
    res.json({
      code: 200,
      data: comments
    });
  } catch (error) {
    console.error('Get comments by position error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get comments by position',
      error: error.message
    });
  }
});

router.post('/batch-resolve', async (req, res) => {
  try {
    const { doc_id, comment_ids, user_id } = req.body;
    
    if (!doc_id || !comment_ids || !Array.isArray(comment_ids)) {
      return res.status(400).json({
        code: 400,
        message: 'doc_id and comment_ids array are required'
      });
    }
    
    const userId = user_id || DEFAULT_USER_ID;
    const result = await commentService.batchResolveComments(
      doc_id,
      comment_ids,
      userId
    );
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    console.error('Batch resolve comments error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to batch resolve comments',
      error: error.message
    });
  }
});

router.get('/recent/:user_id', async (req, res) => {
  try {
    const { user_id } = req.params;
    const { limit = 20 } = req.query;
    
    const comments = await commentService.getRecentComments(
      user_id,
      parseInt(limit)
    );
    
    res.json({
      code: 200,
      data: comments
    });
  } catch (error) {
    console.error('Get recent comments error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get recent comments',
      error: error.message
    });
  }
});

module.exports = router;
