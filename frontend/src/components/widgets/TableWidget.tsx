import React from 'react';
import { Table } from 'antd';
import type { TableProps } from 'antd';
import Loading from '@/components/Loading';
import type { WidgetProps, TableData } from './types';

const TableWidget: React.FC<WidgetProps<TableData>> = ({
  data,
  config,
  title,
  loading = false,
  height = '100%',
}) => {
  if (loading) {
    return (
      <div className="widget-container">
        {title && <div className="widget-title">{title}</div>}
        <div className="widget-content flex-center">
          <Loading />
        </div>
      </div>
    );
  }

  const columns: TableProps<Record<string, unknown>>['columns'] =
    data?.columns.map((col) => ({
      ...col,
      render: (text: unknown, record: Record<string, unknown>, index: number) => {
        if (config?.columnRender?.[col.key]) {
          return config.columnRender[col.key](text, record, index);
        }
        return text;
      },
    })) || [];

  const pagination = data?.pagination
    ? {
        ...data.pagination,
        showSizeChanger: config?.showSizeChanger !== false,
        showQuickJumper: config?.showQuickJumper === true,
        showTotal:
          config?.showTotal === true
            ? (total: number) => `共 ${total} 条`
            : undefined,
        pageSizeOptions: ['10', '20', '50', '100'],
        ...(config?.pagination as object),
      }
    : false;

  return (
    <div className="widget-container">
      {title && <div className="widget-title">{title}</div>}
      <div className="widget-content" style={{ height }}>
        <Table
          columns={columns}
          dataSource={data?.data || []}
          loading={loading}
          pagination={pagination}
          scroll={{
            x: config?.scrollX || 'max-content',
            y: config?.scrollY,
          }}
          bordered={config?.bordered === true}
          size={config?.size as 'small' | 'middle' | 'large'}
          rowKey={(record) => record.id as string || String(record.key)}
          {...(config?.tableProps as object)}
        />
      </div>
    </div>
  );
};

export default TableWidget;
