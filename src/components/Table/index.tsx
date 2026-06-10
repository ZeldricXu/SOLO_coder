import React, {
  forwardRef,
  useCallback,
  useMemo,
  useState,
  useRef,
} from 'react';
import type { TableProps, SortState, FilterState, Column } from './types';
import { cn } from '@utils/cn';
import { generateId, useEscapeKey } from '@a11y';
import { Pagination } from './Pagination';
import styles from './Table.module.css';

const SortAscIcon: React.FC<{ active?: boolean }> = ({ active }) => (
  <svg
    className={cn(active && styles.sortIconActive)}
    width="10"
    height="10"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="3"
  >
    <polyline points="18 15 12 9 6 15" />
  </svg>
);

const SortDescIcon: React.FC<{ active?: boolean }> = ({ active }) => (
  <svg
    className={cn(active && styles.sortIconActive)}
    width="10"
    height="10"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="3"
  >
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

const FilterIcon: React.FC<{ active?: boolean }> = ({ active }) => (
  <svg
    className={cn(styles.filterIcon, active && styles.filterIconActive)}
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
  >
    <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />
  </svg>
);

export const Table = forwardRef(function Table<T extends Record<string, unknown>>(
  {
    columns,
    dataSource,
    rowKey,
    loading = false,
    emptyText = '暂无数据',
    bordered = false,
    striped = false,
    hoverable = true,
    sortable = true,
    filterable = true,
    pagination = false,
    onPageChange,
    onSortChange,
    onFilterChange,
    rowSelection,
    onRowClick,
    size = 'md',
    className,
  }: TableProps<T>,
  ref: React.ForwardedRef<HTMLDivElement>,
) {
  const tableId = generateId('table');
  const [sortState, setSortState] = useState<SortState | null>(null);
  const [filterState, setFilterState] = useState<FilterState>({});
  const [filterDropdownKey, setFilterDropdownKey] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(
    typeof pagination === 'object' ? pagination.currentPage : 1,
  );
  const [pageSize, setPageSize] = useState(
    typeof pagination === 'object' ? pagination.pageSize : 10,
  );
  const filterDropdownRef = useRef<HTMLDivElement>(null);
  const selectAllCheckboxRef = useRef<HTMLInputElement>(null);

  const getRowKey = useCallback(
    (record: T, index: number): string | number => {
      if (typeof rowKey === 'function') {
        return rowKey(record);
      }
      return (record[rowKey] as string | number) ?? index;
    },
    [rowKey],
  );

  const handleSort = useCallback(
    (key: string) => {
      if (!sortable) return;

      setSortState((prev) => {
        let newSort: SortState | null;
        if (!prev || prev.key !== key) {
          newSort = { key, direction: 'asc' };
        } else if (prev.direction === 'asc') {
          newSort = { key, direction: 'desc' };
        } else if (prev.direction === 'desc') {
          newSort = null;
        } else {
          newSort = { key, direction: 'asc' };
        }
        onSortChange?.(newSort);
        return newSort;
      });
    },
    [sortable, onSortChange],
  );

  const handleFilter = useCallback(
    (key: string, value: string | number | boolean | undefined) => {
      setFilterState((prev) => {
        const newFilters = { ...prev };
        if (value === undefined || value === '') {
          delete newFilters[key];
        } else {
          newFilters[key] = value;
        }
        onFilterChange?.(newFilters);
        return newFilters;
      });
      setFilterDropdownKey(null);
    },
    [onFilterChange],
  );

  const handlePageChange = useCallback(
    (page: number, newPageSize: number) => {
      setCurrentPage(page);
      setPageSize(newPageSize);
      onPageChange?.(page, newPageSize);
    },
    [onPageChange],
  );

  const handleSelectAll = useCallback(
    (checked: boolean) => {
      if (!rowSelection) return;
      if (checked) {
        const allKeys = dataSource.map((record, index) => getRowKey(record, index));
        rowSelection.onChange(allKeys, dataSource);
      } else {
        rowSelection.onChange([], []);
      }
    },
    [rowSelection, dataSource, getRowKey],
  );

  const handleRowSelect = useCallback(
    (key: string | number, record: T, checked: boolean) => {
      if (!rowSelection) return;
      const newSelectedKeys = checked
        ? [...rowSelection.selectedRowKeys, key]
        : rowSelection.selectedRowKeys.filter((k) => k !== key);
      const newSelectedRows = dataSource.filter(
        (r, i) => newSelectedKeys.includes(getRowKey(r, i)),
      );
      rowSelection.onChange(newSelectedKeys, newSelectedRows);
    },
    [rowSelection, dataSource, getRowKey],
  );

  const sortedData = useMemo(() => {
    if (!sortState || !sortState.direction) return dataSource;

    return [...dataSource].sort((a, b) => {
      const column = columns.find((c) => c.key === sortState.key);
      if (!column) return 0;

      const dataIndex = column.dataIndex ?? (column.key as keyof T);
      const aVal = a[dataIndex];
      const bVal = b[dataIndex];

      if (aVal === undefined || aVal === null) return sortState.direction === 'asc' ? -1 : 1;
      if (bVal === undefined || bVal === null) return sortState.direction === 'asc' ? 1 : -1;

      if (typeof aVal === 'number' && typeof bVal === 'number') {
        return sortState.direction === 'asc' ? aVal - bVal : bVal - aVal;
      }

      const aStr = String(aVal).toLowerCase();
      const bStr = String(bVal).toLowerCase();
      return sortState.direction === 'asc'
        ? aStr.localeCompare(bStr)
        : bStr.localeCompare(aStr);
    });
  }, [dataSource, sortState, columns]);

  const filteredData = useMemo(() => {
    if (Object.keys(filterState).length === 0) return sortedData;

    return sortedData.filter((record) =>
      Object.entries(filterState).every(([key, filterValue]) => {
        if (filterValue === undefined || filterValue === '') return true;
        const column = columns.find((c) => c.key === key);
        if (!column) return true;
        const dataIndex = column.dataIndex ?? (key as keyof T);
        const cellValue = record[dataIndex];
        return String(cellValue).toLowerCase().includes(String(filterValue).toLowerCase());
      }),
    );
  }, [sortedData, filterState, columns]);

  const paginatedData = useMemo(() => {
    if (!pagination) return filteredData;

    const start = (currentPage - 1) * pageSize;
    const end = start + pageSize;
    return filteredData.slice(start, end);
  }, [filteredData, pagination, currentPage, pageSize]);

  const allSelected = useMemo(() => {
    if (!rowSelection || paginatedData.length === 0) return false;
    return paginatedData.every((record, index) =>
      rowSelection.selectedRowKeys.includes(getRowKey(record, index)),
    );
  }, [rowSelection, paginatedData, getRowKey]);

  const someSelected = useMemo(() => {
    if (!rowSelection || paginatedData.length === 0) return false;
    return paginatedData.some((record, index) =>
      rowSelection.selectedRowKeys.includes(getRowKey(record, index)),
    );
  }, [rowSelection, paginatedData, getRowKey]);

  React.useEffect(() => {
    if (selectAllCheckboxRef.current) {
      selectAllCheckboxRef.current.indeterminate = someSelected && !allSelected;
    }
  }, [someSelected, allSelected]);

  useEscapeKey(() => setFilterDropdownKey(null), filterDropdownKey !== null);

  const tableClasses = cn(
    styles.table,
    size === 'sm' && styles.sm,
    size === 'lg' && styles.lg,
    bordered && styles.bordered,
    striped && styles.striped,
    hoverable && styles.hoverable,
    loading && styles.loading,
    className,
  );

  const total =
    typeof pagination === 'object' ? pagination.total : filteredData.length;

  return (
    <div ref={ref} className={cn(loading && styles.loading)}>
      {loading && (
        <div className={styles.loadingOverlay}>
          <div className={styles.loadingSpinner} />
          <span className={styles.loadingText}>加载中...</span>
        </div>
      )}

      <div style={{ overflowX: 'auto' }}>
        <table className={tableClasses} role="grid" aria-label="数据表格">
          <thead className={styles.header}>
            <tr role="row">
              {rowSelection ? (
                <th className={cn(styles.headerCell, styles.checkboxCell)} role="columnheader">
                  <input
                    ref={selectAllCheckboxRef}
                    type="checkbox"
                    checked={allSelected}
                    onChange={(e) => handleSelectAll(e.target.checked)}
                    aria-label="全选"
                  />
                </th>
              ) : null}
              {columns.map((column) => {
                const colKey = String(column.key);
                const isSorted = sortState?.key === colKey;
                const isFiltered =
                  filterState[colKey] !== undefined && filterState[colKey] !== '';
                const showSort = sortable && column.sortable !== false;
                const showFilter = filterable && column.filterable !== false;

                return (
                  <th
                    key={colKey}
                    className={cn(
                      styles.headerCell,
                      column.align === 'center' && styles.center,
                      column.align === 'right' && styles.right,
                    )}
                    role="columnheader"
                    aria-sort={
                      isSorted && sortState?.direction
                        ? sortState.direction === 'asc'
                          ? 'ascending'
                          : 'descending'
                        : 'none'
                    }
                    style={{ width: column.width }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--spacing-xs)', justifyContent: column.align || 'flex-start' }}>
                      {showSort ? (
                        <span
                          className={styles.sortableHeader}
                          onClick={() => handleSort(colKey)}
                          role="button"
                          tabIndex={0}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              handleSort(colKey);
                            }
                          }}
                        >
                          {column.title}
                          <span className={styles.sortIcon}>
                            <SortAscIcon active={isSorted && sortState?.direction === 'asc'} />
                            <SortDescIcon active={isSorted && sortState?.direction === 'desc'} />
                          </span>
                        </span>
                      ) : (
                        column.title
                      )}
                      {showFilter ? (
                        <span
                          role="button"
                          tabIndex={0}
                          onClick={(e) => {
                            e.stopPropagation();
                            setFilterDropdownKey(filterDropdownKey === colKey ? null : colKey);
                          }}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              setFilterDropdownKey(filterDropdownKey === colKey ? null : colKey);
                            }
                          }}
                          style={{ cursor: 'pointer', position: 'relative' }}
                          aria-label={`${column.title}筛选`}
                        >
                          <FilterIcon active={isFiltered} />
                          {filterDropdownKey === colKey ? (
                            <div
                              ref={filterDropdownRef}
                              className={styles.filterDropdown}
                              onClick={(e) => e.stopPropagation()}
                            >
                              <input
                                type="text"
                                placeholder="输入筛选内容..."
                                value={String(filterState[colKey] ?? '')}
                                onChange={(e) => handleFilter(colKey, e.target.value)}
                                autoFocus
                                aria-label={`${column.title}筛选输入`}
                              />
                              {column.filterOptions ? (
                                <div style={{ marginTop: 'var(--spacing-xs)' }}>
                                  {column.filterOptions.map((opt) => (
                                    <div
                                      key={String(opt.value)}
                                      className={styles.filterOption}
                                      onClick={() => handleFilter(colKey, opt.value)}
                                      role="option"
                                    >
                                      {opt.label}
                                    </div>
                                  ))}
                                </div>
                              ) : null}
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleFilter(colKey, undefined);
                                }}
                                style={{
                                  marginTop: 'var(--spacing-xs)',
                                  padding: 'var(--spacing-xs) var(--spacing-sm)',
                                  background: 'transparent',
                                  border: 'none',
                                  cursor: 'pointer',
                                  color: 'var(--color-text-secondary)',
                                  fontSize: 'var(--font-size-sm)',
                                }}
                              >
                                清除筛选
                              </button>
                            </div>
                          ) : null}
                        </span>
                      ) : null}
                    </div>
                  </th>
                );
              })}
            </tr>
          </thead>

          <tbody>
            {paginatedData.length === 0 && !loading ? (
              <tr>
                <td
                  colSpan={columns.length + (rowSelection ? 1 : 0)}
                  className={styles.empty}
                  data-testid="empty-state"
                >
                  {emptyText}
                </td>
              </tr>
            ) : (
              paginatedData.map((record, index) => {
                const key = getRowKey(record, index);
                const isSelected = rowSelection?.selectedRowKeys.includes(key) ?? false;

                return (
                  <tr
                    key={String(key)}
                    className={cn(styles.row, isSelected && styles.rowSelected)}
                    role="row"
                    onClick={() => onRowClick?.(record, index)}
                    onKeyDown={(e) => {
                      if ((e.key === 'Enter' || e.key === ' ') && onRowClick) {
                        e.preventDefault();
                        onRowClick(record, index);
                      }
                    }}
                    tabIndex={onRowClick ? 0 : undefined}
                    aria-selected={isSelected}
                  >
                    {rowSelection ? (
                      <td className={cn(styles.cell, styles.checkboxCell)} role="cell">
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={(e) => handleRowSelect(key, record, e.target.checked)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`选择第${index + 1}行`}
                        />
                      </td>
                    ) : null}
                    {columns.map((column) => {
                      const colKey = String(column.key);
                      const dataIndex = column.dataIndex ?? (colKey as keyof T);
                      const value = record[dataIndex];
                      const content = column.render
                        ? column.render(value, record, index)
                        : value;

                      return (
                        <td
                          key={colKey}
                          className={cn(
                            styles.cell,
                            column.align === 'center' && styles.center,
                            column.align === 'right' && styles.right,
                          )}
                          role="cell"
                        >
                          {content}
                        </td>
                      );
                    })}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {pagination ? (
        <Pagination
          currentPage={currentPage}
          pageSize={pageSize}
          total={total}
          onPageChange={handlePageChange}
          size={size}
        />
      ) : null}
    </div>
  );
});

Table.displayName = 'Table';

export type { TableProps, Column, SortState, FilterState, PaginationState };
