const express = require('express');
const router = express.Router();
const Asset = require('../models/Asset');

const defaultAssets = [
  {
    asset_id: 'furniture_chair_01',
    name: '现代办公椅',
    category: 'furniture',
    subcategory: 'seating',
    description: '简约现代风格办公椅，适合办公室场景',
    preview_url: '/previews/chair_01.png',
    model_url: '/models/chair_01.gltf',
    default_transform: {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1, y: 1, z: 1 }
    },
    tags: ['chair', 'office', 'modern']
  },
  {
    asset_id: 'furniture_table_01',
    name: '办公桌',
    category: 'furniture',
    subcategory: 'table',
    description: '标准尺寸办公桌，木质台面',
    preview_url: '/previews/table_01.png',
    model_url: '/models/table_01.gltf',
    default_transform: {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1.5, y: 0.75, z: 0.8 }
    },
    tags: ['table', 'desk', 'office', 'wood']
  },
  {
    asset_id: 'furniture_sofa_01',
    name: '三人沙发',
    category: 'furniture',
    subcategory: 'seating',
    description: '舒适布艺三人沙发，适合客厅场景',
    preview_url: '/previews/sofa_01.png',
    model_url: '/models/sofa_01.gltf',
    default_transform: {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 2.2, y: 0.9, z: 1 }
    },
    tags: ['sofa', 'living', 'fabric', 'comfortable']
  },
  {
    asset_id: 'furniture_cabinet_01',
    name: '文件柜',
    category: 'furniture',
    subcategory: 'storage',
    description: '金属文件柜，三抽屉设计',
    preview_url: '/previews/cabinet_01.png',
    model_url: '/models/cabinet_01.gltf',
    default_transform: {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 0.8, y: 1.2, z: 0.5 }
    },
    tags: ['cabinet', 'storage', 'office', 'metal']
  },
  {
    asset_id: 'door_standard_01',
    name: '标准室内门',
    category: 'door',
    subcategory: 'interior',
    description: '标准尺寸室内木门',
    preview_url: '/previews/door_01.png',
    model_url: '/models/door_01.gltf',
    default_transform: {
      position: { x: 0, y: 0, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 0.9, y: 2.1, z: 0.1 }
    },
    tags: ['door', 'interior', 'wood']
  },
  {
    asset_id: 'window_standard_01',
    name: '标准窗户',
    category: 'window',
    subcategory: 'single',
    description: '单扇玻璃窗，铝合金框架',
    preview_url: '/previews/window_01.png',
    model_url: '/models/window_01.gltf',
    default_transform: {
      position: { x: 0, y: 1, z: 0 },
      rotation: { x: 0, y: 0, z: 0 },
      scale: { x: 1.5, y: 1.2, z: 0.1 }
    },
    tags: ['window', 'glass', 'aluminum']
  }
];

async function initializeDefaultAssets() {
  const existingCount = await Asset.countDocuments();
  if (existingCount === 0) {
    await Asset.insertMany(defaultAssets);
    console.log('Default assets initialized');
  }
}

initializeDefaultAssets();

router.get('/list', async (req, res) => {
  try {
    const { category, subcategory, search, tags } = req.query;
    
    const filter = { is_active: true };
    
    if (category && category !== 'all') {
      filter.category = category;
    }
    
    if (subcategory) {
      filter.subcategory = subcategory;
    }
    
    if (search) {
      filter.$or = [
        { name: { $regex: search, $options: 'i' } },
        { description: { $regex: search, $options: 'i' } }
      ];
    }
    
    if (tags) {
      const tagList = tags.split(',').map(t => t.trim());
      filter.tags = { $in: tagList };
    }
    
    const assets = await Asset.find(filter).sort({ createdAt: -1 });
    
    res.json({
      code: 200,
      data: {
        assets: assets.map(asset => ({
          asset_id: asset.asset_id,
          name: asset.name,
          category: asset.category,
          subcategory: asset.subcategory,
          description: asset.description,
          preview_url: asset.preview_url,
          model_url: asset.model_url,
          default_transform: asset.default_transform,
          tags: asset.tags
        })),
        total: assets.length
      }
    });
  } catch (error) {
    console.error('Error fetching assets:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to fetch assets',
      error: error.message
    });
  }
});

router.get('/:asset_id', async (req, res) => {
  try {
    const { asset_id } = req.params;
    
    const asset = await Asset.findOne({ asset_id, is_active: true });
    
    if (!asset) {
      return res.status(404).json({
        code: 404,
        message: 'Asset not found'
      });
    }
    
    res.json({
      code: 200,
      data: {
        asset: {
          asset_id: asset.asset_id,
          name: asset.name,
          category: asset.category,
          subcategory: asset.subcategory,
          description: asset.description,
          preview_url: asset.preview_url,
          model_url: asset.model_url,
          default_transform: asset.default_transform,
          tags: asset.tags
        }
      }
    });
  } catch (error) {
    console.error('Error fetching asset:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to fetch asset',
      error: error.message
    });
  }
});

router.get('/categories/list', async (req, res) => {
  try {
    const categories = await Asset.aggregate([
      { $match: { is_active: true } },
      { $group: { _id: '$category', count: { $sum: 1 } } },
      { $project: { category: '$_id', count: 1, _id: 0 } }
    ]);
    
    res.json({
      code: 200,
      data: {
        categories: [
          { category: 'all', name: '全部', count: await Asset.countDocuments({ is_active: true }) },
          ...categories.map(c => ({
            category: c.category,
            name: {
              furniture: '家具',
              door: '门窗',
              window: '窗户',
              material: '材质',
              decoration: '装饰品'
            }[c.category] || c.category,
            count: c.count
          }))
        ]
      }
    });
  } catch (error) {
    console.error('Error fetching categories:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to fetch categories',
      error: error.message
    });
  }
});

module.exports = router;
