import React, { useEffect, useState } from 'react';
import useStore from '../store';
import assetService from '../services/assetService';
import geometryEditor from '../services/geometryEditor';

const styles = {
  panel: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    overflow: 'hidden'
  },
  header: {
    padding: '12px 16px',
    borderBottom: '1px solid #0f3460',
    backgroundColor: '#0f3460'
  },
  title: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#e0e0e0',
    margin: 0
  },
  tabs: {
    display: 'flex',
    borderBottom: '1px solid #0f3460',
    padding: '0 8px'
  },
  tab: {
    padding: '8px 16px',
    fontSize: '12px',
    color: '#8892b0',
    cursor: 'pointer',
    borderBottom: '2px solid transparent',
    transition: 'all 0.2s'
  },
  tabActive: {
    color: '#e94560',
    borderBottomColor: '#e94560'
  },
  searchContainer: {
    padding: '12px 16px',
    borderBottom: '1px solid #0f3460'
  },
  searchInput: {
    width: '100%',
    padding: '8px 12px',
    backgroundColor: '#0d1b2a',
    border: '1px solid #0f3460',
    borderRadius: '4px',
    color: '#e0e0e0',
    fontSize: '13px',
    outline: 'none'
  },
  categories: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: '8px',
    padding: '12px 16px',
    borderBottom: '1px solid #0f3460'
  },
  categoryTag: {
    padding: '4px 12px',
    borderRadius: '16px',
    fontSize: '11px',
    cursor: 'pointer',
    backgroundColor: '#0f3460',
    color: '#8892b0',
    border: '1px solid #16213e',
    transition: 'all 0.2s'
  },
  categoryTagActive: {
    backgroundColor: '#e94560',
    color: '#fff',
    borderColor: '#e94560'
  },
  assetList: {
    flex: 1,
    overflowY: 'auto',
    padding: '8px'
  },
  assetItem: {
    display: 'flex',
    alignItems: 'center',
    padding: '10px 12px',
    borderRadius: '6px',
    cursor: 'pointer',
    marginBottom: '4px',
    transition: 'all 0.2s',
    backgroundColor: 'transparent'
  },
  assetItemHover: {
    backgroundColor: '#0f3460'
  },
  assetIcon: {
    width: '36px',
    height: '36px',
    backgroundColor: '#16213e',
    borderRadius: '6px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '18px',
    marginRight: '12px'
  },
  assetInfo: {
    flex: 1
  },
  assetName: {
    fontSize: '13px',
    color: '#e0e0e0',
    marginBottom: '2px'
  },
  assetCategory: {
    fontSize: '11px',
    color: '#8892b0'
  },
  loading: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '20px',
    color: '#8892b0',
    fontSize: '13px'
  },
  empty: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '40px 20px',
    color: '#666',
    fontSize: '13px',
    textAlign: 'center'
  },
  dragHint: {
    padding: '8px 16px',
    fontSize: '11px',
    color: '#8892b0',
    backgroundColor: '#0d1b2a',
    borderTop: '1px solid #0f3460'
  }
};

function AssetPanel() {
  const { assetLibrary, setAssetLibrary, setSelectedCategory } = useStore();
  const [searchTerm, setSearchTerm] = useState('');
  const [hoveredAsset, setHoveredAsset] = useState(null);
  const [activeTab, setActiveTab] = useState('assets');

  useEffect(() => {
    loadAssets();
    loadCategories();
  }, []);

  const loadAssets = async (category = 'all') => {
    try {
      setAssetLibrary({ loading: true });
      await assetService.fetchAssets(category, searchTerm);
    } catch (error) {
      console.error('Failed to load assets:', error);
      setAssetLibrary({ loading: false });
    }
  };

  const loadCategories = async () => {
    try {
      await assetService.fetchCategories();
    } catch (error) {
      console.error('Failed to load categories:', error);
    }
  };

  const handleCategoryClick = (category) => {
    setSelectedCategory(category);
    loadAssets(category);
  };

  const handleSearchChange = (e) => {
    const value = e.target.value;
    setSearchTerm(value);
    loadAssets(assetLibrary.selectedCategory);
  };

  const handleAssetClick = (asset) => {
    console.log('Asset clicked:', asset.name);
  };

  const handleAssetDragStart = (e, asset) => {
    e.dataTransfer.setData('application/json', JSON.stringify({
      type: 'asset',
      asset_id: asset.asset_id,
      asset_data: asset
    }));
    e.dataTransfer.effectAllowed = 'copy';
  };

  const getCategoryIcon = (category) => {
    const icons = {
      furniture: '🪑',
      door: '🚪',
      window: '🪟',
      material: '🎨',
      decoration: '🖼️'
    };
    return icons[category] || '📦';
  };

  const filteredAssets = assetLibrary.assets.filter(asset => {
    if (!searchTerm) return true;
    return asset.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
           asset.description?.toLowerCase().includes(searchTerm.toLowerCase());
  });

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <h3 style={styles.title}>资产库</h3>
      </div>
      
      <div style={styles.tabs}>
        <div
          style={{ ...styles.tab, ...(activeTab === 'assets' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('assets')}
        >
          模型资产
        </div>
        <div
          style={{ ...styles.tab, ...(activeTab === 'materials' ? styles.tabActive : {}) }}
          onClick={() => setActiveTab('materials')}
        >
          材质
        </div>
      </div>
      
      <div style={styles.searchContainer}>
        <input
          type="text"
          style={styles.searchInput}
          placeholder="搜索资产..."
          value={searchTerm}
          onChange={handleSearchChange}
        />
      </div>
      
      {assetLibrary.categories.length > 0 && (
        <div style={styles.categories}>
          {assetLibrary.categories.map((cat) => (
            <div
              key={cat.category}
              style={{
                ...styles.categoryTag,
                ...(assetLibrary.selectedCategory === cat.category ? styles.categoryTagActive : {})
              }}
              onClick={() => handleCategoryClick(cat.category)}
            >
              {cat.name} ({cat.count})
            </div>
          ))}
        </div>
      )}
      
      <div style={styles.assetList}>
        {assetLibrary.loading ? (
          <div style={styles.loading}>加载中...</div>
        ) : filteredAssets.length === 0 ? (
          <div style={styles.empty}>
            {searchTerm ? '未找到匹配的资产' : '暂无可用资产'}
          </div>
        ) : (
          filteredAssets.map((asset) => (
            <div
              key={asset.asset_id}
              style={{
                ...styles.assetItem,
                ...(hoveredAsset === asset.asset_id ? styles.assetItemHover : {})
              }}
              onClick={() => handleAssetClick(asset)}
              onMouseEnter={() => setHoveredAsset(asset.asset_id)}
              onMouseLeave={() => setHoveredAsset(null)}
              draggable
              onDragStart={(e) => handleAssetDragStart(e, asset)}
            >
              <div style={styles.assetIcon}>
                {getCategoryIcon(asset.category)}
              </div>
              <div style={styles.assetInfo}>
                <div style={styles.assetName}>{asset.name}</div>
                <div style={styles.assetCategory}>
                  {asset.tags?.slice(0, 2).join(', ') || asset.category}
                </div>
              </div>
            </div>
          ))
        )}
      </div>
      
      <div style={styles.dragHint}>
        💡 拖拽资产到3D场景中放置
      </div>
    </div>
  );
}

export default AssetPanel;
