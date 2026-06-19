import React from 'react';
import './searchSkeleton.css';

interface SearchSkeletonProps {
  count?: number;
}

export const SearchSkeleton: React.FC<SearchSkeletonProps> = ({ count = 6 }) => {
  return (
    <div className="search-skeleton-container">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="search-skeleton-item">
          <span className="search-skeleton-icon" />
          <div className="search-skeleton-content">
            <div className="search-skeleton-title" />
            <div className="search-skeleton-snippet" />
          </div>
        </div>
      ))}
    </div>
  );
};

export default SearchSkeleton;
