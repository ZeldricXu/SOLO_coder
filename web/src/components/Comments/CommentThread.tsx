import React, { useState } from 'react';
import { useUserStore } from '../../stores/useUserStore';
import { useBoardStore } from '../../stores/useBoardStore';
import type { Comment as CommentType } from '../../types';

interface CommentThreadProps {
  commentId: string;
}

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  top: 80,
  left: 16,
  width: 320,
  maxHeight: 480,
  backgroundColor: '#ffffff',
  borderRadius: 12,
  boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
  border: '1px solid #e5e7eb',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  zIndex: 100,
};

const headerStyle: React.CSSProperties = {
  padding: '12px 16px',
  borderBottom: '1px solid #e5e7eb',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
};

const commentsListStyle: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: 16,
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};

const commentStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
};

const commentContentStyle: React.CSSProperties = {
  flex: 1,
  backgroundColor: '#f9fafb',
  borderRadius: 8,
  padding: 8,
  paddingLeft: 12,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid #d1d5db',
  borderRadius: 8,
  fontSize: 13,
  outline: 'none',
  resize: 'none',
  fontFamily: 'inherit',
};

const buttonStyle = (primary: boolean): React.CSSProperties => ({
  padding: '6px 14px',
  border: 'none',
  borderRadius: 6,
  cursor: 'pointer',
  fontSize: 12,
  fontWeight: 500,
  backgroundColor: primary ? '#3b82f6' : '#f3f4f6',
  color: primary ? '#ffffff' : '#374151',
});

const demoComments: CommentType[] = [
  {
    id: '1',
    threadId: 'demo-thread',
    userId: 'user-1',
    content: '这个设计看起来不错！',
    position: { x: 100, y: 100 },
    resolved: false,
    createdAt: Date.now() - 3600000,
    updatedAt: Date.now() - 3600000,
  },
  {
    id: '2',
    threadId: 'demo-thread',
    userId: 'user-2',
    content: '同意，颜色搭配很好',
    position: { x: 100, y: 100 },
    resolved: false,
    createdAt: Date.now() - 1800000,
    updatedAt: Date.now() - 1800000,
  },
];

const CommentThread: React.FC<CommentThreadProps> = () => {
  const { toggleComments } = useBoardStore();
  const currentUser = useUserStore((state) => state.currentUser);
  const [comments, setComments] = useState<CommentType[]>(demoComments);
  const [newComment, setNewComment] = useState('');
  const [resolved, setResolved] = useState(false);

  const handleSubmit = () => {
    if (!newComment.trim() || !currentUser) return;

    const comment: CommentType = {
      id: crypto.randomUUID(),
      threadId: 'demo-thread',
      userId: currentUser.id,
      content: newComment.trim(),
      position: { x: 0, y: 0 },
      resolved: false,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    setComments([...comments, comment]);
    setNewComment('');
  };

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div style={containerStyle}>
      <div style={headerStyle}>
        <div style={{ fontWeight: 600, fontSize: 14, color: '#111827' }}>
          💬 评论 ({comments.length})
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button
            style={{
              ...buttonStyle(false),
              fontSize: 11,
              padding: '4px 10px',
              backgroundColor: resolved ? '#dcfce7' : '#f3f4f6',
              color: resolved ? '#16a34a' : '#374151',
            }}
            onClick={() => setResolved(!resolved)}
          >
            {resolved ? '✓ 已解决' : '标记解决'}
          </button>
          <button
            style={buttonStyle(false)}
            onClick={toggleComments}
            title="关闭"
          >
            ✕
          </button>
        </div>
      </div>

      <div style={commentsListStyle}>
        {comments.map((comment) => (
          <div key={comment.id} style={commentStyle}>
            <div
              style={{
                width: 28,
                height: 28,
                borderRadius: '50%',
                backgroundColor: '#3b82f6',
                color: 'white',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 11,
                fontWeight: 600,
                flexShrink: 0,
              }}
            >
              U
            </div>
            <div style={commentContentStyle}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                <span style={{ fontWeight: 600, fontSize: 12, color: '#111827' }}>
                </span>
                <span style={{ fontSize: 10, color: '#9ca3af' }}>{formatTime(comment.createdAt)}</span>
              </div>
              <span style={{ fontSize: 13, color: '#374151', lineHeight: 1.5 }}>
                {comment.content}
              </span>
            </div>
          </div>
        ))}
      </div>

      <div
        style={{
          padding: 12,
          borderTop: '1px solid #e5e7eb',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
        }}
      >
        <textarea
          style={inputStyle}
          placeholder="添加评论..."
          value={newComment}
          onChange={(e) => setNewComment(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSubmit();
            }
          }}
          rows={2}
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button style={buttonStyle(true)} onClick={handleSubmit} disabled={!newComment.trim()}>
            发送
          </button>
        </div>
      </div>
    </div>
  );
};

export default CommentThread;
