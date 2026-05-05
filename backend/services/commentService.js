const { Document, Comment } = require('../models');
const logger = require('../utils/logger');

const commentService = {
  async createComment(docId, user, content, position = {}, parentCommentId = null) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkCommentPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限对此文档添加评论');
      }

      if (!content || content.trim() === '') {
        throw new Error('评论内容不能为空');
      }

      if (parentCommentId) {
        const parentComment = await Comment.findOne({ comment_id: parentCommentId });
        if (!parentComment) {
          throw new Error('父评论不存在');
        }
      }

      const comment = new Comment({
        doc_id: docId,
        parent_comment_id: parentCommentId || null,
        content: content.trim(),
        author: user,
        position: {
          line: position.line || 0,
          start_char: position.start_char || 0,
          end_char: position.end_char || 0,
          selected_text: position.selected_text || ''
        },
        status: 'open'
      });

      await comment.save();

      logger.info(`评论创建成功: doc_id=${docId}, comment_id=${comment.comment_id}, user=${user}`);
      
      return {
        success: true,
        data: {
          comment_id: comment.comment_id,
          doc_id: docId,
          parent_comment_id: comment.parent_comment_id,
          content: comment.content,
          author: comment.author,
          position: comment.position,
          status: comment.status,
          created_at: comment.created_at
        }
      };
    } catch (error) {
      logger.error(`评论创建失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getDocumentComments(docId, user, status = 'all') {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限查看此文档的评论');
      }

      let query = { doc_id: docId, parent_comment_id: null };
      
      if (status !== 'all') {
        query.status = status;
      }

      const comments = await Comment.find(query)
        .sort({ created_at: -1 })
        .lean();

      const commentsWithReplies = await Promise.all(
        comments.map(async (comment) => {
          const replies = await Comment.find({ 
            doc_id: docId, 
            parent_comment_id: comment.comment_id 
          })
          .sort({ created_at: 1 })
          .lean();

          return {
            ...comment,
            replies: replies
          };
        })
      );

      return {
        success: true,
        data: {
          doc_id: docId,
          comments: commentsWithReplies,
          status: status
        }
      };
    } catch (error) {
      logger.error(`获取评论列表失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async updateComment(commentId, user, content) {
    try {
      const comment = await Comment.findOne({ comment_id: commentId });
      
      if (!comment) {
        throw new Error('评论不存在');
      }

      if (comment.author !== user) {
        throw new Error('无权限修改此评论');
      }

      if (!content || content.trim() === '') {
        throw new Error('评论内容不能为空');
      }

      comment.content = content.trim();
      await comment.save();

      logger.info(`评论更新成功: comment_id=${commentId}, user=${user}`);
      
      return {
        success: true,
        data: {
          comment_id: comment.comment_id,
          content: comment.content,
          updated_at: comment.updated_at
        }
      };
    } catch (error) {
      logger.error(`评论更新失败: ${error.message}`, { commentId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async deleteComment(commentId, user) {
    try {
      const comment = await Comment.findOne({ comment_id: commentId });
      
      if (!comment) {
        throw new Error('评论不存在');
      }

      const document = await Document.findOne({ doc_id: comment.doc_id });
      if (!document) {
        throw new Error('文档不存在');
      }

      if (comment.author !== user && 
          !document.permissions.admin.includes(user) &&
          document.author !== user) {
        throw new Error('无权限删除此评论');
      }

      const replies = await Comment.find({ parent_comment_id: commentId });
      for (const reply of replies) {
        await Comment.deleteOne({ comment_id: reply.comment_id });
      }

      await Comment.deleteOne({ comment_id: commentId });

      logger.info(`评论删除成功: comment_id=${commentId}, user=${user}`);
      
      return {
        success: true,
        message: '评论已删除',
        deleted_replies: replies.length
      };
    } catch (error) {
      logger.error(`评论删除失败: ${error.message}`, { commentId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async resolveComment(commentId, user) {
    try {
      const comment = await Comment.findOne({ comment_id: commentId });
      
      if (!comment) {
        throw new Error('评论不存在');
      }

      const document = await Document.findOne({ doc_id: comment.doc_id });
      if (!document) {
        throw new Error('文档不存在');
      }

      if (document.author !== user && 
          !document.permissions.admin.includes(user)) {
        throw new Error('无权限解决此评论');
      }

      comment.status = 'resolved';
      comment.resolved_by = user;
      comment.resolved_at = new Date();
      await comment.save();

      logger.info(`评论已解决: comment_id=${commentId}, user=${user}`);
      
      return {
        success: true,
        data: {
          comment_id: comment.comment_id,
          status: comment.status,
          resolved_by: comment.resolved_by,
          resolved_at: comment.resolved_at
        }
      };
    } catch (error) {
      logger.error(`解决评论失败: ${error.message}`, { commentId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async closeComment(commentId, user) {
    try {
      const comment = await Comment.findOne({ comment_id: commentId });
      
      if (!comment) {
        throw new Error('评论不存在');
      }

      const document = await Document.findOne({ doc_id: comment.doc_id });
      if (!document) {
        throw new Error('文档不存在');
      }

      if (document.author !== user && 
          !document.permissions.admin.includes(user)) {
        throw new Error('无权限关闭此评论');
      }

      comment.status = 'closed';
      comment.resolved_by = user;
      comment.resolved_at = new Date();
      await comment.save();

      logger.info(`评论已关闭: comment_id=${commentId}, user=${user}`);
      
      return {
        success: true,
        data: {
          comment_id: comment.comment_id,
          status: comment.status,
          resolved_by: comment.resolved_by,
          resolved_at: comment.resolved_at
        }
      };
    } catch (error) {
      logger.error(`关闭评论失败: ${error.message}`, { commentId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  checkReadPermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.read.includes(user)) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  },

  checkCommentPermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.read.includes(user)) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  }
};

module.exports = commentService;
