export type SortDirection = 'asc' | 'desc' | null;

export interface SortState {
  key: string;
  direction: SortDirection;
}

export interface FilterState {
  [key: string]: string | number | boolean | undefined;
}

export interface PaginationState {
  currentPage: number;
  pageSize: number;
  total: number;
}

export interface Column<T> {
  key: keyof T | string;
  title: React.ReactNode;
  dataIndex?: keyof T;
  render?: (value: T[keyof T] | undefined, record: T, index: number) => React.ReactNode;
  sortable?: boolean;
  filterable?: boolean;
  filterOptions?: Array<{ label: string; value: string | number | boolean }>;
  width?: string | number;
  align?: 'left' | 'center' | 'right';
  fixed?: 'left' | 'right';
}

export interface TableProps<T> {
  columns: Column<T>[];
  dataSource: T[];
  rowKey: keyof T | ((record: T) => string);
  loading?: boolean;
  emptyText?: React.ReactNode;
  bordered?: boolean;
  striped?: boolean;
  hoverable?: boolean;
  sortable?: boolean;
  filterable?: boolean;
  sortBy?: SortState | null;
  defaultSortBy?: SortState | null;
  filters?: FilterState;
  defaultFilters?: FilterState;
  pagination?: PaginationState | boolean;
  onPageChange?: (page: number, pageSize: number) => void;
  onSortChange?: (sort: SortState | null) => void;
  onFilterChange?: (filters: FilterState) => void;
  rowSelection?: {
    selectedRowKeys: Array<string | number>;
    onChange: (selectedRowKeys: Array<string | number>, selectedRows: T[]) => void;
  };
  onRowClick?: (record: T, index: number) => void;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export interface TableHeaderProps<T> {
  columns: Column<T>[];
  sortState: SortState | null;
  onSort: (key: string) => void;
  filterState: FilterState;
  onFilter: (key: string, value: string | number | boolean | undefined) => void;
  selectAll: boolean;
  onSelectAll: (checked: boolean) => void;
  hasRowSelection: boolean;
}

export interface TableBodyProps<T> {
  columns: Column<T>[];
  dataSource: T[];
  rowKey: keyof T | ((record: T) => string);
  loading: boolean;
  emptyText: React.ReactNode;
  striped: boolean;
  hoverable: boolean;
  onRowClick?: (record: T, index: number) => void;
  selectedRowKeys: Array<string | number>;
  onRowSelect: (key: string | number, record: T, checked: boolean) => void;
  hasRowSelection: boolean;
  size: 'sm' | 'md' | 'lg';
}

export interface PaginationProps {
  currentPage: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number, pageSize: number) => void;
  size?: 'sm' | 'md' | 'lg';
}
