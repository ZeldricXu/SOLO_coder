const commentModel = require('../models/commentModel');
const commitModel = require('../models/commitModel');
const logger = require('../config/logger');

const commentService = {
  async createComment(commentData) {
    try {
      const { commit_id, file_path, line_start, line_end, comment_type, content, author, parent_comment_id } = commentData;
      
      const commit = await commitModel.findById(commit_id);
      if (!commit) {
        throw new Error(`提交不存在: ${commit_id}`);
      }
      
      if (parent_comment_id) {
        const parentComment = await commentModel.findById(parent_comment_id);
        if (!parentComment) {
          throw new Error(`父评论不存在: ${parent_comment_id}`);
        }
      }
      
      const file = await commitModel.getFileByPath(commit_id, file_path);
      if (!file) {
        throw new Error(`文件不在变更范围内: ${file_path}`);
      }
      
      if (file.file_content) {
        const totalLines = file.file_content.split('\n').length;
        if (line_start < 1 || line_start > totalLines) {
          throw new Error(`起始行号无效: ${line_start}, 总行数: ${totalLines}`);
        }
        if (line_end && (line_end < line_start || line_end > totalLines)) {
          throw new Error(`结束行号无效: ${line_end}`);
        }
      }
      
      const comment = await commentModel.create({
        commit_id,
        file_path,
        line_start,
        line_end: line_end || line_start,
        comment_type: comment_type || 'comment',
        content,
        author,
        parent_comment_id
      });
      
      logger.info('审查意见创建成功: comment_id=%s, commit_id=%s, file=%s',
        comment.comment_id, commit_id, file_path);
      
      return comment;
    } catch (error) {
      logger.error('创建审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getComment(comment_id) {
    try {
      const comment = await commentModel.findById(comment_id);
      if (!comment) {
        throw new Error(`审查意见不存在: ${comment_id}`);
      }
      return comment;
    } catch (error) {
      logger.error('获取审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getCommentsByCommit(commit_id, includeReplies = true) {
    try {
      if (includeReplies) {
        return await commentModel.getCommentsWithReplies(commit_id);
      }
      return await commentModel.findByCommitId(commit_id);
    } catch (error) {
      logger.error('按提交获取审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getCommentsByFile(commit_id, file_path) {
    try {
      const comments = await commentModel.findByCommitAndFile(commit_id, file_path);
      
      const rootComments = comments.filter(c => !c.parent_comment_id);
      
      const commentsWithReplies = await Promise.all(
        rootComments.map(async (comment) => {
          const replies = comments.filter(c => c.parent_comment_id === comment.comment_id);
          return {
            ...comment,
            replies
          };
        })
      );
      
      return commentsWithReplies;
    } catch (error) {
      logger.error('按文件获取审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getCommentsByLineRange(commit_id, file_path, line_start, line_end) {
    try {
      return await commentModel.findByLineRange(commit_id, file_path, line_start, line_end || line_start);
    } catch (error) {
      logger.error('按行范围获取审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getReplies(parent_comment_id) {
    try {
      return await commentModel.findReplies(parent_comment_id);
    } catch (error) {
      logger.error('获取回复意见失败: %s', error.message);
      throw error;
    }
  },

  async updateCommentStatus(comment_id, status) {
    try {
      const validStatuses = ['open', 'resolved', 'dismissed'];
      if (!validStatuses.includes(status)) {
        throw new Error(`无效的意见状态: ${status}`);
      }
      
      const updatedComment = await commentModel.updateStatus(comment_id, status);
      
      if (!updatedComment) {
        throw new Error('更新意见状态失败');
      }
      
      logger.info('审查意见状态更新: comment_id=%s, status=%s', comment_id, status);
      
      return updatedComment;
    } catch (error) {
      logger.error('更新审查意见状态失败: %s', error.message);
      throw error;
    }
  },

  async updateCommentContent(comment_id, content) {
    try {
      const comment = await this.getComment(comment_id);
      
      if (comment.status !== 'open') {
        throw new Error('只能编辑未处理的意见');
      }
      
      const updatedComment = await commentModel.updateContent(comment_id, content);
      
      if (!updatedComment) {
        throw new Error('更新意见内容失败');
      }
      
      logger.info('审查意见内容更新: comment_id=%s', comment_id);
      
      return updatedComment;
    } catch (error) {
      logger.error('更新审查意见内容失败: %s', error.message);
      throw error;
    }
  },

  async deleteComment(comment_id) {
    try {
      const comment = await this.getComment(comment_id);
      
      if (comment.status === 'resolved') {
        throw new Error('不能删除已解决的意见');
      }
      
      const deletedComment = await commentModel.delete(comment_id);
      
      if (!deletedComment) {
        throw new Error('删除意见失败');
      }
      
      logger.info('审查意见已删除: comment_id=%s', comment_id);
      
      return deletedComment;
    } catch (error) {
      logger.error('删除审查意见失败: %s', error.message);
      throw error;
    }
  },

  async resolveComment(comment_id) {
    return await this.updateCommentStatus(comment_id, 'resolved');
  },

  async dismissComment(comment_id) {
    return await this.updateCommentStatus(comment_id, 'dismissed');
  },

  async reopenComment(comment_id) {
    return await this.updateCommentStatus(comment_id, 'open');
  },

  async getStatistics(commit_id = null) {
    try {
      return await commentModel.getStatistics(commit_id);
    } catch (error) {
      logger.error('获取审查意见统计失败: %s', error.message);
      throw error;
    }
  },

  async replyToComment(parent_comment_id, replyData) {
    try {
      const parentComment = await this.getComment(parent_comment_id);
      
      return await this.createComment({
        ...replyData,
        commit_id: parentComment.commit_id,
        file_path: parentComment.file_path,
        line_start: parentComment.line_start,
        line_end: parentComment.line_end,
        parent_comment_id
      });
    } catch (error) {
      logger.error('回复审查意见失败: %s', error.message);
      throw error;
    }
  },

  async getCommentWithDetails(comment_id) {
    try {
      const comment = await this.getComment(comment_id);
      const replies = await this.getReplies(comment_id);
      
      return {
        ...comment,
        replies
      };
    } catch (error) {
      logger.error('获取意见详情失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = commentService;
