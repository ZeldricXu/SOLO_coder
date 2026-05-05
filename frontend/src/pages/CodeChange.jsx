import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  List,
  Tag,
  Spin,
  Empty,
  Button,
  Input,
  Space,
  Tabs,
  Typography,
  Divider,
  Modal,
  Form,
  Select,
  message
} from 'antd';
import {
  FileTextOutlined,
  PlusCircleOutlined,
  MinusCircleOutlined,
  EditOutlined,
  DeleteOutlined,
  MessageOutlined,
  SearchOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { codeApi, reviewApi } from '../services/api';
import useAppStore from '../store/appStore';

const { TextArea } = Input;
const { Text, Title } = Typography;

const mockCommitData = {
  commit_id: 'commit_abc123',
  repo_id: 'repo_project_01',
  author: 'developer_01',
  commit_time: '2026-05-04T14:10:00Z',
  message: '添加用户认证功能',
  files: [
    {
      id: 1,
      file_path: 'src/main.py',
      language: 'python',
      status: 'modified',
      file_content: `from flask import Flask, request, jsonify
import jwt
import bcrypt
from datetime import datetime, timedelta

app = Flask(__name__)
app.config['SECRET_KEY'] = 'your-secret-key-here'

users = []

@app.route('/register', methods=['POST'])
def register():
    data = request.get_json()
    
    if not data or 'username' not in data or 'password' not in data:
        return jsonify({'error': '用户名和密码不能为空'}), 400
    
    for user in users:
        if user['username'] == data['username']:
            return jsonify({'error': '用户名已存在'}), 400
    
    hashed_password = bcrypt.hashpw(data['password'].encode('utf-8'), bcrypt.gensalt())
    
    users.append({
        'username': data['username'],
        'password': hashed_password,
        'created_at': datetime.now()
    })
    
    return jsonify({'message': '注册成功'}), 201

@app.route('/login', methods=['POST'])
def login():
    data = request.get_json()
    
    if not data or 'username' not in data or 'password' not in data:
        return jsonify({'error': '用户名和密码不能为空'}), 400
    
    user = None
    for u in users:
        if u['username'] == data['username']:
            user = u
            break
    
    if user is None:
        return jsonify({'error': '用户不存在'}), 401
    
    if not bcrypt.checkpw(data['password'].encode('utf-8'), user['password']):
        return jsonify({'error': '密码错误'}), 401
    
    token = jwt.encode({
        'username': user['username'],
        'exp': datetime.utcnow() + timedelta(hours=24)
    }, app.config['SECRET_KEY'], algorithm='HS256')
    
    return jsonify({'token': token}), 200

if __name__ == '__main__':
    app.run(debug=True)`,
      old_content: `from flask import Flask, request, jsonify
import jwt
import bcrypt
from datetime import datetime, timedelta

app = Flask(__name__)
app.config['SECRET_KEY'] = 'your-secret-key-here'

users = []

@app.route('/register', methods=['POST'])
def register():
    data = request.get_json()
    
    if not data or 'username' not in data or 'password' not in data:
        return jsonify({'error': '用户名和密码不能为空'}), 400
    
    for user in users:
        if user['username'] == data['username']:
            return jsonify({'error': '用户名已存在'}), 400
    
    hashed_password = bcrypt.hashpw(data['password'].encode('utf-8'), bcrypt.gensalt())
    
    users.append({
        'username': data['username'],
        'password': hashed_password,
        'created_at': datetime.now()
    })
    
    return jsonify({'message': '注册成功'}), 201

if __name__ == '__main__':
    app.run(debug=True)`
    },
    {
      id: 2,
      file_path: 'src/utils.py',
      language: 'python',
      status: 'added',
      file_content: `import re
from datetime import datetime

def validate_email(email):
    pattern = r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$'
    return re.match(pattern, email) is not None

def format_date(date):
    if isinstance(date, str):
        try:
            date = datetime.fromisoformat(date)
        except ValueError:
            return date
    
    return date.strftime('%Y-%m-%d %H:%M:%S')

def generate_random_string(length=8):
    import random
    import string
    letters = string.ascii_letters + string.digits
    return ''.join(random.choice(letters) for _ in range(length))`,
      old_content: null
    },
    {
      id: 3,
      file_path: 'src/config.py',
      language: 'python',
      status: 'deleted',
      file_content: null,
      old_content: `# 配置文件
DEBUG = True
DATABASE_URL = 'sqlite:///app.db'
SECRET_KEY = 'old-secret-key'`
    }
  ]
};

function CodeChange() {
  const [loading, setLoading] = useState(false);
  const [commitData, setCommitData] = useState(mockCommitData);
  const [selectedFile, setSelectedFile] = useState(mockCommitData.files[0]);
  const [diff, setDiff] = useState(null);
  const [comments, setComments] = useState([]);
  const [commentModalVisible, setCommentModalVisible] = useState(false);
  const [selectedLines, setSelectedLines] = useState({ start: 1, end: 1 });
  const [form] = Form.useForm();
  
  const { 
    currentCommit, 
    setCurrentCommit, 
    setSelectedFile: storeSetSelectedFile,
    setFiles,
    setComments: storeSetComments
  } = useAppStore();

  useEffect(() => {
    setCurrentCommit(commitData);
    setFiles(commitData.files);
    generateDiff(selectedFile);
    loadComments(commitData.commit_id, selectedFile.file_path);
  }, []);

  const generateDiff = (file) => {
    if (!file) return;
    
    const oldLines = file.old_content ? file.old_content.split('\n') : [];
    const newLines = file.file_content ? file.file_content.split('\n') : [];
    
    const diffLines = [];
    let oldIdx = 0;
    let newIdx = 0;
    
    while (oldIdx < oldLines.length || newIdx < newLines.length) {
      if (oldIdx >= oldLines.length) {
        diffLines.push({
          type: 'added',
          oldLine: null,
          newLine: newIdx + 1,
          content: newLines[newIdx]
        });
        newIdx++;
      } else if (newIdx >= newLines.length) {
        diffLines.push({
          type: 'removed',
          oldLine: oldIdx + 1,
          newLine: null,
          content: oldLines[oldIdx]
        });
        oldIdx++;
      } else if (oldLines[oldIdx] === newLines[newIdx]) {
        diffLines.push({
          type: 'unchanged',
          oldLine: oldIdx + 1,
          newLine: newIdx + 1,
          content: oldLines[oldIdx]
        });
        oldIdx++;
        newIdx++;
      } else {
        let matchFound = false;
        
        for (let lookAhead = 1; lookAhead <= 5 && oldIdx + lookAhead < oldLines.length; lookAhead++) {
          if (oldLines[oldIdx + lookAhead] === newLines[newIdx]) {
            for (let i = 0; i < lookAhead; i++) {
              diffLines.push({
                type: 'removed',
                oldLine: oldIdx + i + 1,
                newLine: null,
                content: oldLines[oldIdx + i]
              });
            }
            oldIdx += lookAhead;
            matchFound = true;
            break;
          }
        }
        
        if (!matchFound) {
          for (let lookAhead = 1; lookAhead <= 5 && newIdx + lookAhead < newLines.length; lookAhead++) {
            if (newLines[newIdx + lookAhead] === oldLines[oldIdx]) {
              for (let i = 0; i < lookAhead; i++) {
                diffLines.push({
                  type: 'added',
                  oldLine: null,
                  newLine: newIdx + i + 1,
                  content: newLines[newIdx + i]
                });
              }
              newIdx += lookAhead;
              matchFound = true;
              break;
            }
          }
        }
        
        if (!matchFound) {
          diffLines.push({
            type: 'removed',
            oldLine: oldIdx + 1,
            newLine: null,
            content: oldLines[oldIdx]
          });
          diffLines.push({
            type: 'added',
            oldLine: null,
            newLine: newIdx + 1,
            content: newLines[newIdx]
          });
          oldIdx++;
          newIdx++;
        }
      }
    }
    
    setDiff(diffLines);
  };

  const loadComments = async (commit_id, file_path) => {
    try {
      const mockComments = [
        {
          comment_id: 'comment_001',
          commit_id: 'commit_abc123',
          file_path: 'src/main.py',
          line_start: 25,
          line_end: 35,
          comment_type: 'suggestion',
          content: '建议将认证逻辑拆分为独立模块，便于后续维护和测试。',
          author: 'reviewer_01',
          status: 'open',
          created_at: '2026-05-04T14:20:00Z',
          replies: [
            {
              comment_id: 'comment_002',
              content: '同意，我会在下一个提交中重构这部分代码。',
              author: 'developer_01',
              status: 'open',
              created_at: '2026-05-04T14:30:00Z'
            }
          ]
        },
        {
          comment_id: 'comment_003',
          commit_id: 'commit_abc123',
          file_path: 'src/main.py',
          line_start: 10,
          line_end: 10,
          comment_type: 'issue',
          content: 'SECRET_KEY 不应该硬编码在代码中，建议使用环境变量。',
          author: 'reviewer_01',
          status: 'open',
          created_at: '2026-05-04T14:15:00Z',
          replies: []
        }
      ];
      setComments(mockComments.filter(c => c.file_path === file_path));
    } catch (error) {
      console.error('加载评论失败:', error);
    }
  };

  const handleFileSelect = (file) => {
    setSelectedFile(file);
    storeSetSelectedFile(file);
    generateDiff(file);
    loadComments(commitData.commit_id, file.file_path);
  };

  const getStatusTag = (status) => {
    const statusMap = {
      'added': { color: 'green', text: '新增', icon: <PlusCircleOutlined /> },
      'modified': { color: 'blue', text: '修改', icon: <EditOutlined /> },
      'deleted': { color: 'red', text: '删除', icon: <DeleteOutlined /> }
    };
    const info = statusMap[status] || statusMap['modified'];
    return <Tag color={info.color} icon={info.icon}>{info.text}</Tag>;
  };

  const getDiffLineClass = (type) => {
    const classMap = {
      'added': 'diff-line-added diff-type-added',
      'removed': 'diff-line-removed diff-type-removed',
      'unchanged': 'diff-line-unchanged diff-type-unchanged'
    };
    return classMap[type] || '';
  };

  const handleLineClick = (line) => {
    if (line.newLine) {
      setSelectedLines({ start: line.newLine, end: line.newLine });
    }
  };

  const handleCreateComment = async (values) => {
    try {
      const newComment = {
        comment_id: `comment_${Date.now()}`,
        commit_id: commitData.commit_id,
        file_path: selectedFile.file_path,
        line_start: selectedLines.start,
        line_end: selectedLines.end,
        comment_type: values.comment_type,
        content: values.content,
        author: 'current_user',
        status: 'open',
        created_at: new Date().toISOString(),
        replies: []
      };
      
      setComments([...comments, newComment]);
      message.success('评论创建成功');
      setCommentModalVisible(false);
      form.resetFields();
    } catch (error) {
      message.error('创建评论失败');
    }
  };

  const handleResolveComment = async (comment_id) => {
    try {
      setComments(comments.map(c => 
        c.comment_id === comment_id ? { ...c, status: 'resolved' } : c
      ));
      message.success('评论已标记为已解决');
    } catch (error) {
      message.error('操作失败');
    }
  };

  const stats = {
    added: commitData.files.filter(f => f.status === 'added').length,
    modified: commitData.files.filter(f => f.status === 'modified').length,
    deleted: commitData.files.filter(f => f.status === 'deleted').length
  };

  return (
    <div>
      <Card>
        <Title level={4}>
          提交: {commitData.commit_id}
        </Title>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>
            <Text strong>提交信息:</Text> {commitData.message}
          </div>
          <div>
            <Text strong>作者:</Text> {commitData.author}
          </div>
          <div>
            <Text strong>提交时间:</Text> {new Date(commitData.commit_time).toLocaleString()}
          </div>
        </Space>
        
        <Divider />
        
        <Space>
          <Tag color="green">{stats.added} 个新增文件</Tag>
          <Tag color="blue">{stats.modified} 个修改文件</Tag>
          <Tag color="red">{stats.deleted} 个删除文件</Tag>
        </Space>
      </Card>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} md={8}>
          <Card 
            title="变更文件列表"
            extra={
              <Button 
                type="text" 
                size="small" 
                icon={<ReloadOutlined />}
              />
            }
          >
            <List
              dataSource={commitData.files}
              renderItem={(file) => (
                <div 
                  className={`file-list-item ${selectedFile?.id === file.id ? 'active' : ''}`}
                  onClick={() => handleFileSelect(file)}
                  style={{ cursor: 'pointer' }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <FileTextOutlined style={{ color: '#1890ff' }} />
                      <Text ellipsis style={{ maxWidth: 180 }}>
                        {file.file_path}
                      </Text>
                    </div>
                    {getStatusTag(file.status)}
                  </div>
                  {comments.filter(c => c.file_path === file.file_path).length > 0 && (
                    <div style={{ marginTop: 4 }}>
                      <Tag icon={<MessageOutlined />} color="blue">
                        {comments.filter(c => c.file_path === file.file_path).length} 条评论
                      </Tag>
                    </div>
                  )}
                </div>
              )}
            />
          </Card>
        </Col>

        <Col xs={24} md={16}>
          <Card 
            title={
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>{selectedFile?.file_path}</span>
                <Space>
                  {getStatusTag(selectedFile?.status)}
                  <Tag color="orange">{selectedFile?.language}</Tag>
                </Space>
              </div>
            }
            extra={
              <Button 
                type="primary" 
                size="small" 
                icon={<MessageOutlined />}
                onClick={() => setCommentModalVisible(true)}
              >
                添加评论
              </Button>
            }
          >
            {diff ? (
              <div className="diff-container" style={{ maxHeight: 600, overflow: 'auto' }}>
                {diff.map((line, index) => (
                  <div 
                    key={index} 
                    className={`diff-line ${getDiffLineClass(line.type)}`}
                    onClick={() => handleLineClick(line)}
                    style={{ cursor: 'pointer' }}
                  >
                    <span className="diff-line-number">
                      {line.oldLine || ''}
                    </span>
                    <span className="diff-line-number">
                      {line.newLine || ''}
                    </span>
                    <span className="diff-line-content">
                      {line.type === 'added' ? '+ ' : line.type === 'removed' ? '- ' : '  '}
                      {line.content || ' '}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <Empty description="暂无差异数据" />
            )}
          </Card>

          {comments.length > 0 && (
            <Card 
              title={`审查意见 (${comments.length})`}
              style={{ marginTop: 16 }}
            >
              <List
                dataSource={comments}
                renderItem={(comment) => (
                  <div 
                    className={`comment-card ${
                      comment.status === 'resolved' ? 'comment-resolved' : 
                      comment.status === 'dismissed' ? 'comment-dismissed' : 'comment-open'
                    }`}
                    style={{ 
                      padding: 16, 
                      borderRadius: 8, 
                      border: '1px solid #f0f0f0',
                      marginBottom: 12
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <div style={{ flex: 1 }}>
                        <div style={{ marginBottom: 8 }}>
                          <Space>
                            <Text strong>{comment.author}</Text>
                            <Tag color={
                              comment.comment_type === 'issue' ? 'red' :
                              comment.comment_type === 'suggestion' ? 'orange' : 'blue'
                            }>
                              {comment.comment_type === 'issue' ? '问题' :
                               comment.comment_type === 'suggestion' ? '建议' : '评论'}
                            </Tag>
                            <Text type="secondary">
                              行 {comment.line_start}{comment.line_end !== comment.line_start ? `-${comment.line_end}` : ''}
                            </Text>
                            <Tag color={
                              comment.status === 'resolved' ? 'green' :
                              comment.status === 'dismissed' ? 'default' : 'orange'
                            }>
                              {comment.status === 'resolved' ? '已解决' :
                               comment.status === 'dismissed' ? '已忽略' : '待处理'}
                            </Tag>
                          </Space>
                        </div>
                        <Text>{comment.content}</Text>
                        
                        {comment.replies && comment.replies.length > 0 && (
                          <div style={{ marginTop: 12, paddingLeft: 24, borderLeft: '2px solid #e8e8e8' }}>
                            {comment.replies.map((reply, idx) => (
                              <div key={idx} style={{ marginBottom: 8 }}>
                                <Space>
                                  <Text strong>{reply.author}</Text>
                                  <Text type="secondary" style={{ fontSize: 12 }}>
                                    {new Date(reply.created_at).toLocaleString()}
                                  </Text>
                                </Space>
                                <div style={{ marginTop: 4 }}>
                                  <Text>{reply.content}</Text>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                      
                      {comment.status !== 'resolved' && (
                        <Button 
                          type="link" 
                          size="small"
                          onClick={() => handleResolveComment(comment.comment_id)}
                        >
                          标记已解决
                        </Button>
                      )}
                    </div>
                  </div>
                )}
              />
            </Card>
          )}
        </Col>
      </Row>

      <Modal
        title="添加审查意见"
        open={commentModalVisible}
        onCancel={() => setCommentModalVisible(false)}
        onOk={() => form.submit()}
        okText="提交"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreateComment}
        >
          <Form.Item label="行号范围">
            <Space>
              <Input
                type="number"
                value={selectedLines.start}
                onChange={(e) => setSelectedLines({ ...selectedLines, start: parseInt(e.target.value) || 1 })}
                style={{ width: 100 }}
              />
              <Text>-</Text>
              <Input
                type="number"
                value={selectedLines.end}
                onChange={(e) => setSelectedLines({ ...selectedLines, end: parseInt(e.target.value) || selectedLines.start })}
                style={{ width: 100 }}
              />
            </Space>
          </Form.Item>
          
          <Form.Item
            name="comment_type"
            label="意见类型"
            initialValue="comment"
          >
            <Select>
              <Select.Option value="comment">评论</Select.Option>
              <Select.Option value="suggestion">建议</Select.Option>
              <Select.Option value="issue">问题</Select.Option>
            </Select>
          </Form.Item>
          
          <Form.Item
            name="content"
            label="意见内容"
            rules={[{ required: true, message: '请输入意见内容' }]}
          >
            <TextArea rows={4} placeholder="请输入您的审查意见..." />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default CodeChange;
