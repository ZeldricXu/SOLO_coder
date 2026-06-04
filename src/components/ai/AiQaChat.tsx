'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Send, Bot, User, Trash2, MessageCircle, X, Loader2, ExternalLink, FileText } from 'lucide-react';
import { trpc } from '@/components/providers/TRPCProvider';
import type { DocumentReference, Message } from '@/lib/aiqa/types';

interface AiQaChatProps {
  spaceId: string;
  isEnabled: boolean;
  onClose?: () => void;
}

interface ChatMessage extends Message {
  isLoading?: boolean;
}

export function AiQaChat({ spaceId, isEnabled, onClose }: AiQaChatProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sessionId, setSessionId] = useState<string | undefined>();
  const [isLoading, setIsLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const askMutation = trpc.aiqa.ask.useMutation();
  const getSessionsQuery = trpc.aiqa.getSessions.useQuery(
    { spaceId, limit: 20 },
    { enabled: isEnabled }
  );
  const getSessionMessagesQuery = trpc.aiqa.getSessionMessages.useQuery(
    { sessionId: sessionId || '' },
    { enabled: isEnabled && !!sessionId }
  );
  const deleteSessionMutation = trpc.aiqa.deleteSession.useMutation();

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  useEffect(() => {
    if (getSessionMessagesQuery.data?.success && getSessionMessagesQuery.data.data) {
      setMessages(getSessionMessagesQuery.data.data as ChatMessage[]);
    }
  }, [getSessionMessagesQuery.data]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!input.trim() || isLoading || !isEnabled) return;

    const userContent = input.trim();
    const userMessage: ChatMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: userContent,
      createdAt: new Date(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInput('');
    setIsLoading(true);

    const loadingMessage: ChatMessage = {
      id: 'loading',
      role: 'assistant',
      content: '',
      createdAt: new Date(),
      isLoading: true,
    };
    setMessages(prev => [...prev, loadingMessage]);

    try {
      const result = await askMutation.mutateAsync({
        spaceId,
        question: userContent,
        sessionId,
        topK: 5,
      });

      if (result.success && result.data) {
        setSessionId(result.data.sessionId);
        const assistantMessage: ChatMessage = {
          id: result.data.messageId,
          role: 'assistant',
          content: result.data.answer,
          references: result.data.references,
          createdAt: new Date(),
        };
        setMessages(prev => prev.filter(m => m.id !== 'loading').concat(assistantMessage));
      } else {
        const errorMessage: ChatMessage = {
          id: Date.now().toString(),
          role: 'assistant',
          content: result.error || '抱歉，出现了错误，请稍后重试。',
          createdAt: new Date(),
        };
        setMessages(prev => prev.filter(m => m.id !== 'loading').concat(errorMessage));
      }
    } catch (error) {
      const errorMessage: ChatMessage = {
        id: Date.now().toString(),
        role: 'assistant',
        content: '抱歉，AI服务暂时不可用，请稍后重试。',
        createdAt: new Date(),
      };
      setMessages(prev => prev.filter(m => m.id !== 'loading').concat(errorMessage));
    } finally {
      setIsLoading(false);
      inputRef.current?.focus();
    }
  };

  const handleNewChat = () => {
    setSessionId(undefined);
    setMessages([]);
    setInput('');
  };

  const handleSessionClick = (id: string) => {
    setSessionId(id);
  };

  const handleDeleteSession = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await deleteSessionMutation.mutateAsync({ sessionId: id });
      if (sessionId === id) {
        handleNewChat();
      }
      getSessionsQuery.refetch();
    } catch (error) {
      console.error('Failed to delete session:', error);
    }
  };

  const formatReferences = (references: DocumentReference[]) => {
    if (!references || references.length === 0) return null;

    return (
      <div className="mt-4 pt-4 border-t border-gray-200">
        <h4 className="text-sm font-medium text-gray-700 mb-2 flex items-center gap-1">
          <FileText size={14} />
          引用来源
        </h4>
        <div className="space-y-2">
          {references.map((ref, index) => (
            <a
              key={ref.documentId}
              href={ref.url || `#`}
              className="block p-2 bg-gray-50 rounded hover:bg-gray-100 transition-colors group"
            >
              <div className="flex items-start gap-2">
                <span className="text-xs font-medium text-blue-600 bg-blue-50 px-1.5 py-0.5 rounded">
                  [{index + 1}]
                </span>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 truncate group-hover:text-blue-600">
                    {ref.title}
                  </p>
                  <p className="text-xs text-gray-500 mt-1 line-clamp-2">
                    {ref.snippet}
                  </p>
                </div>
                <ExternalLink size={14} className="text-gray-400 flex-shrink-0 mt-1" />
              </div>
            </a>
          ))}
        </div>
      </div>
    );
  };

  if (!isEnabled) {
    return (
      <div className="flex flex-col items-center justify-center h-64 p-6 text-center">
        <Bot size={48} className="text-gray-300 mb-4" />
        <h3 className="text-lg font-medium text-gray-600 mb-2">AI问答未启用</h3>
        <p className="text-sm text-gray-500">
          请联系空间管理员启用AI问答功能
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-white rounded-lg border border-gray-200">
      <div className="flex items-center justify-between p-4 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <Bot size={20} className="text-blue-600" />
          <h3 className="font-semibold text-gray-800">AI 知识库助手</h3>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleNewChat}
            className="p-2 text-gray-500 hover:text-blue-600 hover:bg-blue-50 rounded transition-colors"
            title="新建对话"
          >
            <MessageCircle size={18} />
          </button>
          {onClose && (
            <button
              onClick={onClose}
              className="p-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded transition-colors"
              title="关闭"
            >
              <X size={18} />
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className="w-56 border-r border-gray-200 overflow-y-auto bg-gray-50">
          <div className="p-3">
            <button
              onClick={handleNewChat}
              className="w-full py-2 px-3 text-sm font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-lg transition-colors mb-3"
            >
              + 新建对话
            </button>
            <div className="space-y-1">
              {getSessionsQuery.data?.data?.map((session: { id: string; title: string }) => (
                <div
                  key={session.id}
                  onClick={() => handleSessionClick(session.id)}
                  className={`group flex items-center justify-between p-2 rounded-lg cursor-pointer transition-colors ${
                    sessionId === session.id
                      ? 'bg-blue-100 text-blue-700'
                      : 'hover:bg-gray-100 text-gray-700'
                  }`}
                >
                  <span className="text-sm truncate flex-1">
                    {session.title}
                  </span>
                  <button
                    onClick={(e) => handleDeleteSession(session.id, e)}
                    className="opacity-0 group-hover:opacity-100 p-1 text-gray-400 hover:text-red-600 transition-all"
                    title="删除对话"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="flex-1 flex flex-col">
          <div className="flex-1 overflow-y-auto p-4 space-y-4">
            {messages.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-center">
                <Bot size={64} className="text-gray-200 mb-4" />
                <h3 className="text-xl font-semibold text-gray-700 mb-2">
                  欢迎使用 AI 知识库助手
                </h3>
                <p className="text-gray-500 max-w-md">
                  你可以问我关于这个知识库的任何问题。我会基于知识库中的文档内容为你提供准确的回答。
                </p>
                <div className="mt-6 grid grid-cols-2 gap-3 w-full max-w-lg">
                  {['这个项目的架构是怎样的？', '如何部署这个应用？', '常见问题有哪些？', '最新的更新日志是什么？'].map((q) => (
                    <button
                      key={q}
                      onClick={() => setInput(q)}
                      className="p-3 text-left text-sm text-gray-600 bg-gray-50 hover:bg-blue-50 hover:text-blue-600 rounded-lg transition-colors"
                    >
                      {q}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((message) => (
                <div
                  key={message.id}
                  className={`flex items-start gap-3 ${
                    message.role === 'user' ? 'flex-row-reverse' : ''
                  }`}
                >
                  <div
                    className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${
                      message.role === 'user'
                        ? 'bg-blue-600 text-white'
                        : 'bg-gray-100 text-gray-600'
                    }`}
                  >
                    {message.role === 'user' ? (
                      <User size={16} />
                    ) : (
                      <Bot size={16} />
                    )}
                  </div>
                  <div
                    className={`max-w-[80%] ${
                      message.role === 'user' ? 'items-end' : 'items-start'
                    }`}
                  >
                    <div
                      className={`p-3 rounded-lg ${
                        message.role === 'user'
                          ? 'bg-blue-600 text-white'
                          : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {message.isLoading ? (
                        <div className="flex items-center gap-2 text-gray-500">
                          <Loader2 size={16} className="animate-spin" />
                          <span>思考中...</span>
                        </div>
                      ) : (
                        <div className="prose prose-sm max-w-none">
                          {message.content.split('\n').map((line, i) => (
                            <p key={i} className="whitespace-pre-wrap">
                              {line}
                            </p>
                          ))}
                        </div>
                      )}
                    </div>
                    {message.references && formatReferences(message.references)}
                  </div>
                </div>
              ))
            )}
            <div ref={messagesEndRef} />
          </div>

          <form onSubmit={handleSubmit} className="p-4 border-t border-gray-200">
            <div className="flex items-center gap-3">
              <input
                ref={inputRef}
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="输入你的问题..."
                disabled={isLoading}
                className="flex-1 px-4 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:opacity-50 disabled:cursor-not-allowed"
              />
              <button
                type="submit"
                disabled={!input.trim() || isLoading}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2"
              >
                {isLoading ? (
                  <Loader2 size={18} className="animate-spin" />
                ) : (
                  <Send size={18} />
                )}
                发送
              </button>
            </div>
            <p className="text-xs text-gray-500 mt-2 text-center">
              AI 回答基于知识库内容，仅供参考
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
