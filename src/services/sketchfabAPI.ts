import type {
  SketchfabSearchParams,
  SketchfabSearchResponse,
  SketchfabModel,
} from '@/types/sketchfab';

const SKETCHFAB_API_BASE = 'https://api.sketchfab.com/v3';

export class SketchfabAPIService {
  private apiToken: string | null = null;

  setToken(token: string): void {
    this.apiToken = token;
  }

  private getAuthHeaders(): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.apiToken) {
      headers['Authorization'] = `Token ${this.apiToken}`;
    }
    return headers;
  }

  async searchModels(params: SketchfabSearchParams = {}): Promise<SketchfabSearchResponse> {
    const queryParams = new URLSearchParams();

    if (params.q) queryParams.set('q', params.q);
    if (params.categories) queryParams.set('categories', params.categories);
    if (params.licenses) queryParams.set('licenses', params.licenses);
    if (params.downloadable !== undefined) {
      queryParams.set('downloadable', String(params.downloadable));
    }
    if (params.animated !== undefined) {
      queryParams.set('animated', String(params.animated));
    }
    if (params.staffpicked !== undefined) {
      queryParams.set('staffpicked', String(params.staffpicked));
    }
    if (params.sort_by) queryParams.set('sort_by', params.sort_by);
    if (params.cursor) queryParams.set('cursor', params.cursor);
    if (params.per_page) queryParams.set('per_page', String(params.per_page));

    queryParams.set('type', 'models');

    const url = `${SKETCHFAB_API_BASE}/search?${queryParams.toString()}`;
    const response = await fetch(url, {
      method: 'GET',
      headers: this.getAuthHeaders(),
    });

    if (!response.ok) {
      throw new Error(`Sketchfab API error: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }

  async getModel(uid: string): Promise<SketchfabModel> {
    const url = `${SKETCHFAB_API_BASE}/models/${uid}`;
    const response = await fetch(url, {
      method: 'GET',
      headers: this.getAuthHeaders(),
    });

    if (!response.ok) {
      throw new Error(`Sketchfab API error: ${response.status} ${response.statusText}`);
    }

    return response.json();
  }

  async getModelDownloadUrl(uid: string): Promise<string> {
    const url = `${SKETCHFAB_API_BASE}/models/${uid}/download`;
    const response = await fetch(url, {
      method: 'GET',
      headers: this.getAuthHeaders(),
    });

    if (!response.ok) {
      throw new Error(
        `Failed to get download URL (${response.status}). Model may not be downloadable or requires authentication.`
      );
    }

    const data = await response.json();
    return data.gltf?.url || data.url || '';
  }
}

export const sketchfabAPI = new SketchfabAPIService();
