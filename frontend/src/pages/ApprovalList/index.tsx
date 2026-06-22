import { useState, useEffect } from 'react'
import {
  Table,
  Button,
  Space,
  Tag,
  Modal,
  Input,
  message,
  Card,
  Row,
  Col,
  Statistic,
} from 'antd'
import {
  CheckOutlined,
  CloseOutlined,
  EyeOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { approvalApi } from '@/api'
import type { Approval, ApprovalStatus } from '@/types'

const { TextArea } = Input

const ApprovalList: React.FC = () => {
  const [loading, setLoading] = useState(false)
  const [data, setData] = useState<Approval[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [status, setStatus] = useState<ApprovalStatus | ''>('')
  const [detailModal, setDetailModal] = useState(false)
  const [currentApproval, setCurrentApproval] = useState<Approval | null>(null)
  const [rejectModal, setRejectModal] = useState(false)
  const [rejectReason, setRejectReason] = useState('')
  const [stats, setStats] = useState({
    total: 0,
    pending: 0,
    approved: 0,
    rejected: 0,
  })

  useEffect(() => {
    loadData()
  }, [page, pageSize, status])

  const loadData = async () => {
    setLoading(true)
    try {
      const res = await approvalApi.list({
        status: status || undefined,
        page,
        page_size: pageSize,
      })
      if (res.data) {
        setData(res.data.data || [])
        setTotal(res.data.pagination.total || 0)

        const allData = res.data.data || []
        setStats({
          total: res.data.pagination.total || 0,
          pending: allData.filter(a => a.status === 'PENDING').length,
          approved: allData.filter(a => a.status === 'APPROVED').length,
          rejected: allData.filter(a => a.status === 'REJECTED').length,
        })
      }
    } catch (err) {
      console.error('Load approvals error:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleApprove = async (id: string) => {
    Modal.confirm({
      title: '确认审批通过',
      content: '审批通过后，开关将自动开启，是否继续？',
      okText: '确认通过',
      cancelText: '取消',
      onOk: async () => {
        try {
          await approvalApi.approve(id)
          message.success('审批已通过')
          loadData()
        } catch (err) {
          console.error('Approve error:', err)
        }
      },
    })
  }

  const handleReject = (approval: Approval) => {
    setCurrentApproval(approval)
    setRejectReason('')
    setRejectModal(true)
  }

  const confirmReject = async () => {
    if (!rejectReason.trim()) {
      message.warning('请输入拒绝原因')
      return
    }
    try {
      await approvalApi.reject(currentApproval!.id, rejectReason)
      message.success('已拒绝')
      setRejectModal(false)
      loadData()
    } catch (err) {
      console.error('Reject error:', err)
    }
  }

  const viewDetail = (approval: Approval) => {
    setCurrentApproval(approval)
    setDetailModal(true)
  }

  const getStatusTag = (status: string) => {
    const configs: Record<string, { color: string; icon: React.ReactNode; text: string }> = {
      PENDING: { color: 'orange', icon: <ClockCircleOutlined />, text: '待审批' },
      APPROVED: { color: 'green', icon: <CheckCircleOutlined />, text: '已通过' },
      REJECTED: { color: 'red', icon: <CloseCircleOutlined />, text: '已拒绝' },
      CANCELLED: { color: 'default', icon: <CloseCircleOutlined />, text: '已取消' },
    }
    const config = configs[status] || configs.CANCELLED
    return (
      <Tag color={config.color}>
        {config.icon} {config.text}
      </Tag>
    )
  }

  const columns: ColumnsType<Approval> = [
    {
      title: '审批标题',
      dataIndex: 'title',
      key: 'title',
      width: 200,
      render: (text, record) => (
        <a onClick={() => viewDetail(record)}>{text}</a>
      ),
    },
    {
      title: '开关信息',
      key: 'switch',
      width: 200,
      render: (_, record) => (
        <Space direction="vertical" size="small">
          <span>开关: {record.switch_key}</span>
          <span>名称: {record.switch_name}</span>
        </Space>
      ),
    },
    {
      title: '申请人',
      dataIndex: 'requester',
      key: 'requester',
      width: 100,
    },
    {
      title: '审批人',
      dataIndex: 'approver',
      key: 'approver',
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (text) => getStatusTag(text),
    },
    {
      title: '变更内容',
      key: 'content',
      width: 150,
      ellipsis: true,
      render: (_, record) => {
        const target = record.change_content?.target_enabled
        return target ? '开启开关' : '关闭开关'
      },
    },
    {
      title: '拒绝原因',
      dataIndex: 'reject_reason',
      key: 'reject_reason',
      width: 150,
      ellipsis: true,
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      key: 'created_at',
      width: 160,
      render: (text) => dayjs(text).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => viewDetail(record)}
          >
            详情
          </Button>
          {record.status === 'PENDING' && (
            <>
              <Button
                type="text"
                size="small"
                icon={<CheckOutlined />}
                onClick={() => handleApprove(record.id)}
              >
                通过
              </Button>
              <Button
                type="text"
                size="small"
                danger
                icon={<CloseOutlined />}
                onClick={() => handleReject(record)}
              >
                拒绝
              </Button>
            </>
          )}
        </Space>
      ),
    },
  ]

  const filterOptions = [
    { label: '全部', value: '' },
    { label: '待审批', value: 'PENDING' },
    { label: '已通过', value: 'APPROVED' },
    { label: '已拒绝', value: 'REJECTED' },
  ]

  return (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="审批总数" value={stats.total} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="待审批"
              value={stats.pending}
              valueStyle={{ color: '#fa8c16' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已通过"
              value={stats.approved}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已拒绝"
              value={stats.rejected}
              valueStyle={{ color: '#ff4d4f' }}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <Space style={{ marginBottom: 16 }}>
          <span>状态筛选:</span>
          {filterOptions.map(opt => (
            <Button
              key={opt.value}
              type={status === opt.value ? 'primary' : 'default'}
              onClick={() => {
                setStatus(opt.value as ApprovalStatus | '')
                setPage(1)
              }}
            >
              {opt.label}
            </Button>
          ))}
        </Space>

        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (p, ps) => {
              setPage(p)
              setPageSize(ps)
            },
          }}
          scroll={{ x: 1300 }}
        />
      </Card>

      <Modal
        title="审批详情"
        open={detailModal}
        onCancel={() => setDetailModal(false)}
        footer={[
          <Button key="close" onClick={() => setDetailModal(false)}>
            关闭
          </Button>,
        ]}
        width={600}
      >
        {currentApproval && (
          <div>
            <p><strong>标题:</strong> {currentApproval.title}</p>
            <p><strong>说明:</strong> {currentApproval.description || '-'}</p>
            <p><strong>开关:</strong> {currentApproval.switch_key} ({currentApproval.switch_name})</p>
            <p><strong>申请人:</strong> {currentApproval.requester}</p>
            <p><strong>审批人:</strong> {currentApproval.approver}</p>
            <p><strong>状态:</strong> {getStatusTag(currentApproval.status)}</p>
            <p><strong>目标操作:</strong> {currentApproval.change_content?.target_enabled ? '开启开关' : '关闭开关'}</p>
            <p><strong>创建时间:</strong> {dayjs(currentApproval.created_at).format('YYYY-MM-DD HH:mm:ss')}</p>
            {currentApproval.approved_at && (
              <p><strong>审批时间:</strong> {dayjs(currentApproval.approved_at).format('YYYY-MM-DD HH:mm:ss')}</p>
            )}
            {currentApproval.reject_reason && (
              <p><strong>拒绝原因:</strong> {currentApproval.reject_reason}</p>
            )}
          </div>
        )}
      </Modal>

      <Modal
        title="拒绝审批"
        open={rejectModal}
        onCancel={() => setRejectModal(false)}
        onOk={confirmReject}
        okText="确认拒绝"
        cancelText="取消"
      >
        <TextArea
          rows={4}
          value={rejectReason}
          onChange={(e) => setRejectReason(e.target.value)}
          placeholder="请输入拒绝原因"
          maxLength={500}
          showCount
        />
      </Modal>
    </div>
  )
}

export default ApprovalList
