import React from 'react';
import { Breadcrumb } from 'antd';
import { HomeOutlined } from '@ant-design/icons';
import type { BreadcrumbProps } from 'antd';

interface PageHeaderProps {
  title: string;
  subTitle?: string;
  breadcrumbItems?: BreadcrumbProps['items'];
  extra?: React.ReactNode;
}

const PageHeader: React.FC<PageHeaderProps> = ({ title, subTitle, breadcrumbItems, extra }) => {
  const defaultItems: BreadcrumbProps['items'] = [
    {
      href: '/',
      title: <HomeOutlined />,
    },
    {
      title,
    },
  ];

  return (
    <div style={{ marginBottom: 24 }}>
      <Breadcrumb items={breadcrumbItems || defaultItems} style={{ marginBottom: 16 }} />
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h1 style={{ fontSize: 20, fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>
            {title}
          </h1>
          {subTitle && (
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', margin: '8px 0 0 0' }}>
              {subTitle}
            </p>
          )}
        </div>
        {extra && <div>{extra}</div>}
      </div>
    </div>
  );
};

export default PageHeader;
