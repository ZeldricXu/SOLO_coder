import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Table } from '@components/Table';
import { createTableColumns, createTableData, TableRecord } from './factories';
import React from 'react';

describe('Table Component', () => {
  const columns = createTableColumns();
  const data = createTableData(5);

  describe('基础渲染', () => {
    it('渲染表格表头', () => {
      render(<Table columns={columns} dataSource={data} rowKey="id" />);

      expect(screen.getByText('ID')).toBeInTheDocument();
      expect(screen.getByText('Name')).toBeInTheDocument();
      expect(screen.getByText('Age')).toBeInTheDocument();
      expect(screen.getByText('Email')).toBeInTheDocument();
      expect(screen.getByText('Status')).toBeInTheDocument();
      expect(screen.getByText('Created At')).toBeInTheDocument();
    });

    it('渲染表格数据行', () => {
      render(<Table columns={columns} dataSource={data} rowKey="id" />);

      data.forEach((record) => {
        expect(screen.getByText(record.name)).toBeInTheDocument();
        expect(screen.getByText(record.email)).toBeInTheDocument();
      });
    });

    it('rowKey作为行的key', () => {
      const { container } = render(<Table columns={columns} dataSource={data} rowKey="id" />);
      const rows = container.querySelectorAll('tbody tr');
      expect(rows).toHaveLength(data.length);
    });
  });

  describe('排序功能', () => {
    it('点击表头触发升序排序', async () => {
      const handleSortChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          onSortChange={handleSortChange}
        />,
      );

      const nameHeader = screen.getByText('Name').closest('.sortableHeader');
      fireEvent.click(nameHeader!);

      await waitFor(() => {
        expect(handleSortChange).toHaveBeenCalledWith({ key: 'name', direction: 'asc' });
      });
    });

    it('再次点击触发降序排序', async () => {
      const handleSortChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          onSortChange={handleSortChange}
        />,
      );

      const nameHeader = screen.getByText('Name').closest('.sortableHeader');

      fireEvent.click(nameHeader!);
      await waitFor(() => {
        expect(handleSortChange).toHaveBeenCalledWith({ key: 'name', direction: 'asc' });
      });

      fireEvent.click(nameHeader!);
      await waitFor(() => {
        expect(handleSortChange).toHaveBeenCalledWith({ key: 'name', direction: 'desc' });
      });
    });

    it('第三次点击取消排序', async () => {
      const handleSortChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          onSortChange={handleSortChange}
        />,
      );

      const nameHeader = screen.getByText('Name').closest('.sortableHeader');

      fireEvent.click(nameHeader!);
      fireEvent.click(nameHeader!);
      fireEvent.click(nameHeader!);

      await waitFor(() => {
        expect(handleSortChange).toHaveBeenLastCalledWith(null);
      });
    });

    it('排序后数据按列正确排列', () => {
      const testData: TableRecord[] = [
        { id: 1, name: 'Charlie', age: 30, email: 'c@test.com', status: 'active', createdAt: '2024-01-03' },
        { id: 2, name: 'Alice', age: 25, email: 'a@test.com', status: 'active', createdAt: '2024-01-01' },
        { id: 3, name: 'Bob', age: 35, email: 'b@test.com', status: 'active', createdAt: '2024-01-02' },
      ];

      render(<Table columns={columns} dataSource={testData} rowKey="id" sortable />);

      const nameHeader = screen.getByText('Name').closest('.sortableHeader');
      fireEvent.click(nameHeader!);

      const rows = screen.getAllByRole('row').slice(1);
      const names = rows.map((row) => row.cells[1].textContent);

      expect(names).toEqual(['Alice', 'Bob', 'Charlie']);
    });

    it('数字列排序正确', () => {
      const testData: TableRecord[] = [
        { id: 1, name: 'A', age: 30, email: 'a@test.com', status: 'active', createdAt: '2024-01-01' },
        { id: 2, name: 'B', age: 25, email: 'b@test.com', status: 'active', createdAt: '2024-01-01' },
        { id: 3, name: 'C', age: 35, email: 'c@test.com', status: 'active', createdAt: '2024-01-01' },
      ];

      render(<Table columns={columns} dataSource={testData} rowKey="id" sortable />);

      const ageHeader = screen.getByText('Age').closest('.sortableHeader');
      fireEvent.click(ageHeader!);

      const rows = screen.getAllByRole('row').slice(1);
      const ages = rows.map((row) => Number(row.cells[2].textContent));

      expect(ages).toEqual([25, 30, 35]);
    });

    it('降序排列正确', () => {
      const testData: TableRecord[] = [
        { id: 1, name: 'A', age: 30, email: 'a@test.com', status: 'active', createdAt: '2024-01-01' },
        { id: 2, name: 'B', age: 25, email: 'b@test.com', status: 'active', createdAt: '2024-01-01' },
        { id: 3, name: 'C', age: 35, email: 'c@test.com', status: 'active', createdAt: '2024-01-01' },
      ];

      render(<Table columns={columns} dataSource={testData} rowKey="id" sortable />);

      const ageHeader = screen.getByText('Age').closest('.sortableHeader');
      fireEvent.click(ageHeader!);
      fireEvent.click(ageHeader!);

      const rows = screen.getAllByRole('row').slice(1);
      const ages = rows.map((row) => Number(row.cells[2].textContent));

      expect(ages).toEqual([35, 30, 25]);
    });

    it('不可排序列点击无反应', () => {
      const handleSortChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          onSortChange={handleSortChange}
        />,
      );

      const emailHeader = screen.getByText('Email').closest('th');
      const sortableHeader = emailHeader?.querySelector('.sortableHeader');
      expect(sortableHeader).not.toBeInTheDocument();
      
      fireEvent.click(emailHeader!);
      expect(handleSortChange).not.toHaveBeenCalled();
    });
  });

  describe('边界条件', () => {
    it('空数据时展示Empty占位图', () => {
      render(
        <Table
          columns={columns}
          dataSource={[]}
          rowKey="id"
          emptyText={<div>暂无数据</div>}
        />,
      );

      expect(screen.getByTestId('empty-state')).toBeInTheDocument();
      expect(screen.getByText('暂无数据')).toBeInTheDocument();
    });

    it('空数据默认提示', () => {
      render(<Table columns={columns} dataSource={[]} rowKey="id" />);
      expect(screen.getByText('暂无数据')).toBeInTheDocument();
    });

    it('loading状态显示加载中', () => {
      render(<Table columns={columns} dataSource={data} rowKey="id" loading />);
      expect(screen.getByText('加载中...')).toBeInTheDocument();
    });
  });

  describe('表格样式', () => {
    it('斑马纹样式', () => {
      const { container } = render(
        <Table columns={columns} dataSource={data} rowKey="id" striped />,
      );
      const table = container.querySelector('table');
      expect(table).toHaveClass('striped');
      const evenRows = container.querySelectorAll('tbody tr:nth-child(even)');
      expect(evenRows.length).toBeGreaterThan(0);
    });

    it('悬停高亮', () => {
      const { container } = render(
        <Table columns={columns} dataSource={data} rowKey="id" hoverable />,
      );
      const table = container.querySelector('table');
      expect(table).toHaveClass('hoverable');
      const firstRow = container.querySelector('tbody tr');
      expect(firstRow).toHaveClass('row');
    });

    it('边框样式', () => {
      const { container } = render(
        <Table columns={columns} dataSource={data} rowKey="id" bordered />,
      );
      expect(container.querySelector('table')).toHaveClass('bordered');
    });

    it('不同尺寸渲染正确', () => {
      const { container, rerender } = render(
        <Table columns={columns} dataSource={data} rowKey="id" size="sm" />,
      );
      expect(container.querySelector('table')).toHaveClass('sm');

      rerender(<Table columns={columns} dataSource={data} rowKey="id" size="lg" />);
      expect(container.querySelector('table')).toHaveClass('lg');
    });
  });

  describe('行选择', () => {
    it('全选功能', () => {
      const handleSelectionChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          rowSelection={{
            selectedRowKeys: [],
            onChange: handleSelectionChange,
          }}
        />,
      );

      const selectAllCheckbox = screen.getByRole('checkbox', { name: /全选/i });
      fireEvent.click(selectAllCheckbox);

      expect(handleSelectionChange).toHaveBeenCalledWith(
        data.map((d) => d.id),
        data,
      );
    });

    it('单行选择', () => {
      const handleSelectionChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          rowSelection={{
            selectedRowKeys: [],
            onChange: handleSelectionChange,
          }}
        />,
      );

      const firstRowCheckbox = screen.getAllByRole('row')[1].querySelector('input[type="checkbox"]');
      fireEvent.click(firstRowCheckbox!);

      expect(handleSelectionChange).toHaveBeenCalledWith([data[0].id], [data[0]]);
    });

    it('半选状态', () => {
      render(
        <Table
          columns={columns}
          dataSource={data}
          rowKey="id"
          rowSelection={{
            selectedRowKeys: [data[0].id],
            onChange: vi.fn(),
          }}
        />,
      );

      const selectAllCheckbox = screen.getByRole('checkbox', { name: /全选/i }) as HTMLInputElement;
      expect(selectAllCheckbox.indeterminate).toBe(true);
    });
  });

  describe('分页功能', () => {
    it('渲染分页器', () => {
      const handlePageChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={createTableData(20)}
          rowKey="id"
          pagination={{
            currentPage: 1,
            pageSize: 10,
            total: 20,
          }}
          onPageChange={handlePageChange}
        />,
      );

      expect(screen.getByRole('navigation')).toBeInTheDocument();
      expect(screen.getByText('共 20 条')).toBeInTheDocument();
    });

    it('翻页触发onPageChange', () => {
      const handlePageChange = vi.fn();
      render(
        <Table
          columns={columns}
          dataSource={createTableData(20)}
          rowKey="id"
          pagination={{
            currentPage: 1,
            pageSize: 10,
            total: 20,
          }}
          onPageChange={handlePageChange}
        />,
      );

      const nextBtn = screen.getByRole('button', { name: /下一页/i });
      fireEvent.click(nextBtn);

      expect(handlePageChange).toHaveBeenCalledWith(2, 10);
    });
  });

  describe('ARIA无障碍属性', () => {
    it('表格具有正确的role', () => {
      render(<Table columns={columns} dataSource={data} rowKey="id" />);
      expect(screen.getByRole('grid')).toBeInTheDocument();
    });

    it('可排序表头具有正确的aria-sort', async () => {
      render(<Table columns={columns} dataSource={data} rowKey="id" sortable />);

      const nameHeaderCell = screen.getByText('Name').closest('th');
      const nameSortableHeader = screen.getByText('Name').closest('.sortableHeader');
      fireEvent.click(nameSortableHeader!);

      await waitFor(() => {
        expect(nameHeaderCell).toHaveAttribute('aria-sort', 'ascending');
      });
    });
  });
});
