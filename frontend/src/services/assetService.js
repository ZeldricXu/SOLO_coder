import axios from 'axios';
import useStore from '../store';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080/api/v1';

class AssetService {
  constructor() {
    this.api = axios.create({
      baseURL: API_BASE_URL,
      timeout: 10000
    });
  }
  
  async fetchAssets(category = 'all', search = '') {
    useStore.getState().setAssetLibrary({ loading: true });
    
    try {
      const params = {};
      if (category && category !== 'all') {
        params.category = category;
      }
      if (search) {
        params.search = search;
      }
      
      const response = await this.api.get('/assets/list', { params });
      
      if (response.data.code === 200) {
        useStore.getState().setAssetLibrary({
          assets: response.data.data.assets,
          loading: false
        });
        return response.data.data.assets;
      } else {
        throw new Error(response.data.message || 'Failed to fetch assets');
      }
    } catch (error) {
      console.error('Error fetching assets:', error);
      useStore.getState().setAssetLibrary({ loading: false });
      throw error;
    }
  }
  
  async fetchCategories() {
    try {
      const response = await this.api.get('/assets/categories/list');
      
      if (response.data.code === 200) {
        useStore.getState().setAssetLibrary({
          categories: response.data.data.categories
        });
        return response.data.data.categories;
      }
    } catch (error) {
      console.error('Error fetching categories:', error);
    }
  }
  
  async getAsset(assetId) {
    try {
      const response = await this.api.get(`/assets/${assetId}`);
      
      if (response.data.code === 200) {
        return response.data.data.asset;
      } else {
        throw new Error(response.data.message || 'Failed to get asset');
      }
    } catch (error) {
      console.error('Error getting asset:', error);
      throw error;
    }
  }
  
  createObjectFromAsset(asset, position = { x: 0, y: 0, z: 0 }) {
    const objectType = this.getObjectTypeFromCategory(asset.category);
    
    return {
      object_type: objectType,
      asset_id: asset.asset_id,
      transform: {
        position,
        rotation: asset.default_transform?.rotation || { x: 0, y: 0, z: 0 },
        scale: asset.default_transform?.scale || { x: 1, y: 1, z: 1 }
      },
      material_id: asset.default_material_id || 'mat_default_01',
      name: asset.name
    };
  }
  
  getObjectTypeFromCategory(category) {
    const mapping = {
      furniture: 'furniture',
      door: 'door',
      window: 'window',
      material: 'furniture',
      decoration: 'furniture'
    };
    return mapping[category] || 'furniture';
  }
}

const assetService = new AssetService();
export default assetService;
