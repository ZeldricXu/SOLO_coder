import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactQuill from 'react-quill';
import 'react-quill/dist/quill.snow.css';
import {
  Button,
  Card,
  Input,
  Select,
  Tag,
  Space,
  Modal,
  Form,
  Input as AntInput,
  message,
  Divider,
  Comment,
  Avatar,
  List,
  Tooltip,
  Popconfirm,
  Badge,
  Drawer
} from 'antd';
import {
  SaveOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  StarOutlined,
  StarFilled,
  MessageOutlined,
  SendOutlined,
  DeleteOutlined,
  ArrowLeftOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  documentApi,
  versionApi,
  categoryApi,
  commentApi,
  favoriteApi
} from '../api/api';
import { useApp } from '../context/AppContext';
const { TextArea } = AntInput;
const { Option } = Select;

const modules = {
  toolbar: [
    [{ 'header': [1, 2, 3, 4, 5, 6, false] }],
    [{ 'font': [] }],
    [{ 'size': ['small', false, 'large', 'huge'] }],
    ['bold', 'italic', 'underline', 'strike'],
    [{ 'color': [] }, { 'background': [] }],
    [{ 'list': 'ordered' }, { 'list': 'bullet' }],
    [{ 'indent': '-1' }, { 'indent': '+1' }],
    [{ 'direction': 'rtl' }],
    [{ 'align': [] }],
    ['link', 'image', 'video'],
    ['blockquote', 'code-block'],
    [{ 'script': 'sub' }, { 'script': 'super' }],
    ['clean']
  ]
};

const DocumentEditPage = () => {
  const { docId } = useParams();
  const navigate = useNavigate();
  const { currentUser, showNotification } = useApp();
  
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [document, setDocument] = useState(null);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [category, setCategory] = useState('未分类');
  const [tags, setTags] = useState([]);
  const [categories, setCategories] = useState([]);
  const [isFavorite, setIsFavorite] = useState(false);
  const [changeDesc, setChangeDesc] = useState('');
  const [saveModalVisible, setSaveModalVisible] = useState(false);
  const [comments, setComments] = useState([]);
  const [newComment, setNewComment] = useState('');
  const [commentsVisible, setCommentsVisible] = useState(false);
  const [autoSaveTimer, setAutoSaveTimer] = useState(null);
  
  const quillRef = useRef(null);

  useEffect(() => {
    fetchCategories();
    if (docId && docId !== 'new') {
      fetchDocument();
      fetchFavoriteStatus();
      fetchComments();
    }
  }, [docId]);

  const fetchCategories = async () => {
    try {
      const result = await categoryApi.list();
      setCategories([{ category_name: '未分类', category_id: null }, ...(result.data || [])]);
    } catch (error) {
      console.error('获取分类失败:', error);
    }
  };

  const fetchDocument = async () => {
    setLoading(true);
    try {
      const result = await documentApi.get(docId);
      const doc = result.data;
      setDocument(doc);
      setTitle(doc.title);
      setContent(doc.content);
      setCategory(doc.category);
      setTags(doc.tags || []);
    } catch (error) {
      message.error(error.message || '获取文档失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchFavoriteStatus = async () => {
    try {
      const result = await favoriteApi.check(docId);
      setIsFavorite(result.data?.is_favorited || false);
    } catch (error) {
      console.error('检查收藏状态失败:', error);
    }
  };

  const fetchComments = async () => {
    try {
      const result = await commentApi.list(docId, 'open');
      setComments(result.data?.comments || []);
    } catch (error) {
      console.error('获取评论失败:', error);
    }
  };

  const handleSave = () => {
    if (!title?.trim()) {
      message.warning('请输入文档标题');
      return;
    }
    setSaveModalVisible(true);
  };

  const handleConfirmSave = async () => {
    setSaving(true);
    try {
      if (docId && docId !== 'new') {
        await documentApi.edit(docId, {
          title: title.trim(),
          content: content,
          category: category,
          tags: tags,
          change_desc: changeDesc || '内容更新'
        });
        message.success('文档保存成功');
      } else {
        const result = await documentApi.create({
          title: title.trim(),
          content: content,
          category: category,
          tags: tags
        });
        message.success('文档创建成功');
        if (result.data?.doc_id) {
          navigate(`/edit/${result.data.doc_id}`);
        }
      }
      setSaveModalVisible(false);
      setChangeDesc('');
    } catch (error) {
      message.error(error.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleFavorite = async () => {
    if (!docId || docId === 'new') {
      message.warning('请先保存文档');
      return;
    }
    try {
      await favoriteApi.toggle(docId);
      setIsFavorite(!isFavorite);
      message.success(isFavorite ? '已取消收藏' : '已添加收藏');
    } catch (error) {
      message.error(error.message || '操作失败');
    }
  };

  const handleAddComment = async () => {
    if (!newComment?.trim()) {
      message.warning('请输入评论内容');
      return;
    }
    try {
      await commentApi.create(docId, {
        content: newComment.trim()
      });
      message.success('评论发表成功');
      setNewComment('');
      fetchComments();
    } catch (error) {
      message.error(error.message || '发表评论失败');
    }
  };

  const handleResolveComment = async (commentId) => {
    try {
      await commentApi.resolve(commentId);
      message.success('评论已解决');
      fetchComments();
    } catch (error) {
      message.error(error.message || '操作失败');
    }
  };

  const handleDeleteComment = async (commentId) => {
    try {
      await commentApi.delete(commentId);
      message.success('评论已删除');
      fetchComments();
    } catch (error) {
      message.error(error.message || '删除失败');
    }
  };

  const handleViewVersions = () => {
    navigate(`/versions/${docId}`);
  };

  const handleShare = () => {
    navigate(`/share/${docId}`);
  };

  const handleBack = () => {
    navigate('/documents');
  };

  const handleTagsChange = (newTags) => {
    setTags(newTags);
  };

  const handleContentChange = (newContent) => {
    setContent(newContent);
    
    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer);
    }
    
    const timer = setTimeout(() => {
      console.log('Auto-save triggered...');
    }, 30000);
    
    setAutoSaveTimer(timer);
  };

  const openComments = () => {
    setCommentsVisible(true);
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 100 }}>
        加载中...
      </div>
    );
  }

  return (
    <div>
      <Card
        title={
          <Space>
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={handleBack}
            />
            <Input
              placeholder="文档标题"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              style={{ width: 400, fontSize: 16, fontWeight: 500 }}
              bordered={false}
            />
          </Space>
        }
        extra={
          <Space>
            <Select
              value={category}
              onChange={setCategory}
              style={{ width: 150 }}
            >
              {categories.map(cat => (
                <Option key={cat.category_id || 'uncategorized'} value={cat.category_name}>
                  {cat.category_name}
                </Option>
              ))}
            </Select>
            
            <Select
              mode="tags"
              value={tags}
              onChange={handleTagsChange}
              placeholder="添加标签"
              style={{ width: 200 }}
              tokenSeparators={[',']}
            />

            <Tooltip title={isFavorite ? '取消收藏' : '添加收藏'}>
              <Button
                type="text"
                icon={isFavorite ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
                onClick={handleToggleFavorite}
              />
            </Tooltip>

            <Tooltip title="评论">
              <Badge count={comments.length} size="small">
                <Button
                  type="text"
                  icon={<MessageOutlined />}
                  onClick={openComments}
                />
              </Badge>
            </Tooltip>

            <Tooltip title="版本历史">
              <Button
                type="text"
                icon={<HistoryOutlined />}
                onClick={handleViewVersions}
              />
            </Tooltip>

            <Tooltip title="分享">
              <Button
                type="text"
                icon={<ShareAltOutlined />}
                onClick={handleShare}
              />
            </Tooltip>

            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={handleSave}
              loading={saving}
            >
              保存
            </Button>
          </Space>
        }
      >
        {document && (
          <div style={{ marginBottom: 16, color: '#999', fontSize: 12 }}>
            <Space>
              <span>作者: {document.author}</span>
              <span>版本: {document.current_version}</span>
              <span>更新时间: {dayjs(document.updated_at).format('YYYY-MM-DD HH:mm')}</span>
            </Space>
          </div>
        )}
        
        <ReactQuill
          ref={quillRef}
          theme="snow"
          value={content}
          onChange={handleContentChange}
          modules={modules}
          placeholder="开始编辑文档内容..."
        />
      </Card>

      <Modal
        title="保存文档"
        open={saveModalVisible}
        onCancel={() => setSaveModalVisible(false)}
        footer={null}
      >
        <Form layout="vertical">
          <Form.Item label="版本变更描述（可选）">
            <TextArea
              rows={4}
              placeholder="描述本次修改的内容..."
              value={changeDesc}
              onChange={(e) => setChangeDesc(e.target.value)}
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setSaveModalVisible(false)}>取消</Button>
              <Button type="primary" onClick={handleConfirmSave} loading={saving}>
                确认保存
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title="评论列表"
        placement="right"
        width={500}
        onClose={() => setCommentsVisible(false)}
        open={commentsVisible}
        extra={
          <Tag color="blue">{comments.length} 条评论</Tag>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <TextArea
            rows={3}
            placeholder="输入评论内容..."
            value={newComment}
            onChange={(e) => setNewComment(e.target.value)}
            style={{ marginBottom: 8 }}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={handleAddComment}
          >
            发表评论
          </Button>
        </div>

        <Divider />

        <List
          className="comment-list"
          itemLayout="horizontal"
          dataSource={comments}
          locale={{ emptyText: '暂无评论' }}
          renderItem={(item) => (
            <Comment
              actions={[
                <Button
                  type="text"
                  className="comment-action-btn"
                  onClick={() => handleResolveComment(item.comment_id)}
                >
                  解决
                </Button>,
                <Popconfirm
                  title="确定删除这条评论？"
                  onConfirm={() => handleDeleteComment(item.comment_id)}
                >
                  <Button
                    type="text"
                    danger
                    className="comment-action-btn"
                    icon={<DeleteOutlined />}
                  />
                </Popconfirm>
              ]}
              author={item.author}
              avatar={<Avatar>{item.author?.[0]?.toUpperCase() || 'U'}</Avatar>}
              content={item.content}
              datetime={
                <Tooltip title={dayjs(item.created_at).format('YYYY-MM-DD HH:mm:ss')}>
                  {dayjs(item.created_at).fromNow()}
                </Tooltip>
              }
            >
              {item.replies?.map(reply => (
                <Comment
                  key={reply.comment_id}
                  author={reply.author}
                  avatar={<Avatar>{reply.author?.[0]?.toUpperCase() || 'U'}</Avatar>}
                  content={reply.content}
                  datetime={
                    <Tooltip title={dayjs(reply.created_at).format('YYYY-MM-DD HH:mm:ss')}>
                      {dayjs(reply.created_at).fromNow()}
                    </Tooltip>
                  }
                />
              ))}
            </Comment>
          )}
        />
      </Drawer>
    </div>
  );
};

export default DocumentEditPage;
