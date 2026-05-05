import React, { useState, useEffect } from 'react';
import { MessageSquare, Send, User, Clock, Check, Trash2, ChevronRight, Reply } from 'lucide-react';
import { commentApi } from '../lib/api';

function CommentItem({ comment, onReply, onResolve, onDelete, level = 0 }) {
  const [showReply, setShowReply] = useState(false);
  const [replyText, setReplyText] = useState('');

  const handleSendReply = async () => {
    if (!replyText.trim()) return;

    try {
      await onReply(comment.comment_id, replyText);
      setReplyText('');
      setShowReply(false);
    } catch (error) {
      console.error('Failed to send reply:', error);
    }
  };

  return (
    <div className="border-b border-slate-100">
      <div
        className={`p-3 hover:bg-slate-50 transition-colors ${
          comment.is_resolved ? 'opacity-60' : ''
        }`}
        style={{ paddingLeft: `${level * 16 + 12}px` }}
      >
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-start gap-2 flex-1">
            <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0">
              <User size={16} className="text-primary-600" />
            </div>

            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium text-slate-900">
                  {comment.created_by || 'Anonymous'}
                </span>
                <span className="text-xs text-slate-400">
                  {new Date(comment.created_at).toLocaleString('zh-CN')}
                </span>
                {comment.is_resolved && (
                  <span className="px-2 py-0.5 text-xs font-medium bg-green-100 text-green-700 rounded-full">
                    已解决
                  </span>
                )}
              </div>

              {comment.selection_text && (
                <div className="mt-1 p-2 bg-yellow-50 border-l-2 border-yellow-400 rounded-r text-xs text-slate-600 italic">
                  "{comment.selection_text}"
                </div>
              )}

              <p className="text-sm text-slate-700 mt-2">
                {comment.content}
              </p>

              {!comment.is_resolved && (
                <div className="flex items-center gap-3 mt-2">
                  <button
                    onClick={() => setShowReply(!showReply)}
                    className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-700"
                  >
                    <Reply size={12} />
                    回复
                  </button>
                  <button
                    onClick={() => onResolve(comment.comment_id)}
                    className="flex items-center gap-1 text-xs text-slate-500 hover:text-green-600"
                  >
                    <Check size={12} />
                    标记解决
                  </button>
                  <button
                    onClick={() => onDelete(comment.comment_id)}
                    className="flex items-center gap-1 text-xs text-slate-500 hover:text-red-600"
                  >
                    <Trash2 size={12} />
                    删除
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {showReply && (
          <div className="mt-3 ml-10 flex gap-2">
            <input
              type="text"
              value={replyText}
              onChange={(e) => setReplyText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSendReply();
                }
                if (e.key === 'Escape') {
                  setShowReply(false);
                  setReplyText('');
                }
              }}
              placeholder="输入回复内容..."
              className="flex-1 px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:border-primary-500"
            />
            <button
              onClick={handleSendReply}
              disabled={!replyText.trim()}
              className="p-2 bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <Send size={16} />
            </button>
          </div>
        )}
      </div>

      {comment.replies && comment.replies.length > 0 && (
        <div>
          {comment.replies.map((reply) => (
            <CommentItem
              key={reply.comment_id}
              comment={reply}
              onReply={onReply}
              onResolve={onResolve}
              onDelete={onDelete}
              level={level + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function CommentPanel({ documentId, onClose }) {
  const [comments, setComments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [newComment, setNewComment] = useState('');
  const [showResolved, setShowResolved] = useState(false);

  useEffect(() => {
    if (!documentId) return;

    const loadComments = async () => {
      try {
        setLoading(true);
        const response = await commentApi.getByDocument(documentId);
        
        if (response.code === 200) {
          setComments(response.data || []);
        }
      } catch (error) {
        console.error('Failed to load comments:', error);
      } finally {
        setLoading(false);
      }
    };

    loadComments();
  }, [documentId]);

  const handleAddComment = async () => {
    if (!newComment.trim() || !documentId) return;

    try {
      const response = await commentApi.create({
        doc_id: documentId,
        content: newComment,
        created_by: 'user_default'
      });

      if (response.code === 201) {
        setComments(prev => [response.data, ...prev]);
        setNewComment('');
      }
    } catch (error) {
      console.error('Failed to add comment:', error);
    }
  };

  const handleReply = async (parentId, content) => {
    if (!content.trim() || !documentId) return;

    try {
      const response = await commentApi.addReply(parentId, {
        content,
        created_by: 'user_default'
      });

      if (response.code === 201) {
        const updatedComments = comments.map(c => {
          if (c.comment_id === parentId) {
            return {
              ...c,
              replies: [...(c.replies || []), response.data]
            };
          }
          return c;
        });
        setComments(updatedComments);
      }
    } catch (error) {
      console.error('Failed to add reply:', error);
    }
  };

  const handleResolve = async (commentId) => {
    try {
      const response = await commentApi.resolve(commentId, {
        resolved_by: 'user_default'
      });

      if (response.code === 200) {
        const updatedComments = comments.map(c => {
          if (c.comment_id === commentId) {
            return { ...c, is_resolved: true };
          }
          return c;
        });
        setComments(updatedComments);
      }
    } catch (error) {
      console.error('Failed to resolve comment:', error);
    }
  };

  const handleDelete = async (commentId) => {
    if (!window.confirm('确定要删除这条评论吗？')) {
      return;
    }

    try {
      await commentApi.delete(commentId);
      setComments(prev => prev.filter(c => c.comment_id !== commentId));
    } catch (error) {
      console.error('Failed to delete comment:', error);
    }
  };

  const filteredComments = comments.filter(c => 
    showResolved ? true : !c.is_resolved
  );

  if (!documentId) {
    return (
      <div className="flex items-center justify-center h-full text-slate-500 text-sm">
        请先选择一个文档
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-semibold text-slate-700 flex items-center gap-2">
          <MessageSquare size={16} />
          评论 ({comments.filter(c => !c.is_resolved).length})
        </h3>
        
        <button
          onClick={() => setShowResolved(!showResolved)}
          className={`text-xs px-2 py-1 rounded transition-colors ${
            showResolved
              ? 'bg-primary-100 text-primary-700'
              : 'text-slate-500 hover:bg-slate-100'
          }`}
        >
          {showResolved ? '隐藏已解决' : '显示已解决'}
        </button>
      </div>

      <div className="p-3 border-b border-slate-200 bg-slate-50">
        <div className="flex gap-2">
          <input
            type="text"
            value={newComment}
            onChange={(e) => setNewComment(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleAddComment();
              }
            }}
            placeholder="添加评论..."
            className="flex-1 px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:border-primary-500 bg-white"
          />
          <button
            onClick={handleAddComment}
            disabled={!newComment.trim()}
            className="p-2 bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            <Send size={16} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="flex items-center justify-center h-full text-slate-500 text-sm">
            加载中...
          </div>
        ) : filteredComments.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-3 px-4 text-center">
            <MessageSquare size={48} className="text-slate-200" />
            <div>
              <p className="text-sm text-slate-600">暂无评论</p>
              <p className="text-xs text-slate-400 mt-1">在上方输入框中添加第一条评论</p>
            </div>
          </div>
        ) : (
          filteredComments.map((comment) => (
            <CommentItem
              key={comment.comment_id}
              comment={comment}
              onReply={handleReply}
              onResolve={handleResolve}
              onDelete={handleDelete}
              level={0}
            />
          ))
        )}
      </div>
    </div>
  );
}

export default CommentPanel;
