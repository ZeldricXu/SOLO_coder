import React, { useMemo } from 'react';
import type { PaginationProps } from './types';
import { cn } from '@utils/cn';
import styles from './Table.module.css';

export const Pagination: React.FC<PaginationProps> = ({
  currentPage,
  pageSize,
  total,
  onPageChange,
  size = 'md',
}) => {
  const totalPages = useMemo(() => Math.ceil(total / pageSize), [total, pageSize]);

  const getPageNumbers = useMemo(() => {
    const pages: Array<number | string> = [];
    const maxVisible = 5;

    if (totalPages <= maxVisible) {
      for (let i = 1; i <= totalPages; i++) {
        pages.push(i);
      }
    } else {
      pages.push(1);

      let start = Math.max(2, currentPage - 1);
      let end = Math.min(totalPages - 1, currentPage + 1);

      if (currentPage <= 2) {
        end = 4;
      } else if (currentPage >= totalPages - 1) {
        start = totalPages - 3;
      }

      if (start > 2) {
        pages.push('...');
      }

      for (let i = start; i <= end; i++) {
        pages.push(i);
      }

      if (end < totalPages - 1) {
        pages.push('...');
      }

      pages.push(totalPages);
    }

    return pages;
  }, [currentPage, totalPages]);

  const handlePageClick = (page: number) => {
    if (page >= 1 && page <= totalPages && page !== currentPage) {
      onPageChange(page, pageSize);
    }
  };

  const handlePrev = () => {
    if (currentPage > 1) {
      onPageChange(currentPage - 1, pageSize);
    }
  };

  const handleNext = () => {
    if (currentPage < totalPages) {
      onPageChange(currentPage + 1, pageSize);
    }
  };

  const handlePageSizeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    onPageChange(1, Number(e.target.value));
  };

  if (total <= 0) return null;

  return (
    <div className={styles.pagination}>
      <span className={styles.pageInfo}>
        共 {total} 条
      </span>

      <button
        className={cn(styles.pageBtn, size === 'sm' && 'text-sm')}
        onClick={handlePrev}
        disabled={currentPage === 1}
        aria-label="上一页"
      >
        &lt;
      </button>

      {getPageNumbers.map((page, index) =>
        typeof page === 'string' ? (
          <span key={`ellipsis-${index}`} className={styles.pageBtn} style={{ border: 'none', cursor: 'default' }}>
            {page}
          </span>
        ) : (
          <button
            key={page}
            className={cn(
              styles.pageBtn,
              page === currentPage && styles.pageBtnActive,
              size === 'sm' && 'text-sm',
            )}
            onClick={() => handlePageClick(page)}
            aria-current={page === currentPage ? 'page' : undefined}
          >
            {page}
          </button>
        ),
      )}

      <button
        className={cn(styles.pageBtn, size === 'sm' && 'text-sm')}
        onClick={handleNext}
        disabled={currentPage === totalPages}
        aria-label="下一页"
      >
        &gt;
      </button>

      <div className={styles.pageSizeSelect}>
        <label htmlFor="page-size" className="text-sm text-gray-600">
          每页
        </label>
        <select
          id="page-size"
          value={pageSize}
          onChange={handlePageSizeChange}
        >
          {[10, 20, 50, 100].map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
        <span className="text-sm text-gray-600">条</span>
      </div>
    </div>
  );
};
