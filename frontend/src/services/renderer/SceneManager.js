import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { TransformControls } from 'three/examples/jsm/controls/TransformControls.js';
import useStore from '../../store';
import factoryRegistry from '../geometry/FactoryRegistry';
import resourcePool from './ResourcePool';
import frustumCullingManager from './FrustumCulling';

class SceneManager {
  constructor() {
    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.controls = null;
    this.transformControls = null;
    this.container = null;
    this.animationFrameId = null;
    
    this.objectsMap = new Map();
    this.meshesMap = new Map();
    this.invisibleMeshes = new Map();
    
    this.raycaster = new THREE.Raycaster();
    this.mouse = new THREE.Vector2();
    
    this.selectedMesh = null;
    this.isInitialized = false;
    
    this.gridHelper = null;
    this.axisHelper = null;
    this.clock = new THREE.Clock();
    
    this.cullingEnabled = true;
    this.lodEnabled = true;
    this.resourcePoolEnabled = true;
    
    this.frameStats = {
      totalObjects: 0,
      visibleObjects: 0,
      culledObjects: 0,
      lastUpdate: 0
    };
  }
  
  initialize(container) {
    if (this.isInitialized) {
      console.warn('SceneManager already initialized');
      return;
    }
    
    this.container = container;
    
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x1a1a2e);
    
    const width = container.clientWidth;
    const height = container.clientHeight;
    
    this.camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
    this.camera.position.set(10, 10, 10);
    this.camera.lookAt(0, 0, 0);
    
    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: true
    });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    
    container.appendChild(this.renderer.domElement);
    
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.05;
    this.controls.screenSpacePanning = false;
    this.controls.minDistance = 1;
    this.controls.maxDistance = 100;
    this.controls.maxPolarAngle = Math.PI * 0.9;
    
    this.transformControls = new TransformControls(this.camera, this.renderer.domElement);
    this.transformControls.addEventListener('dragging-changed', (event) => {
      this.controls.enabled = !event.value;
    });
    this.transformControls.addEventListener('objectChange', () => {
      this.onTransformChange();
    });
    this.scene.add(this.transformControls);
    
    this.setupLights();
    this.setupGrid();
    this.setupEventListeners();
    this.startAnimation();
    
    this.isInitialized = true;
    console.log('SceneManager initialized with culling and LOD enabled');
  }
  
  setupLights() {
    const ambientLight = new THREE.AmbientLight(0x404040, 0.6);
    this.scene.add(ambientLight);
    
    const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
    directionalLight.position.set(10, 20, 10);
    directionalLight.castShadow = true;
    directionalLight.shadow.mapSize.width = 2048;
    directionalLight.shadow.mapSize.height = 2048;
    directionalLight.shadow.camera.near = 0.5;
    directionalLight.shadow.camera.far = 50;
    directionalLight.shadow.camera.left = -20;
    directionalLight.shadow.camera.right = 20;
    directionalLight.shadow.camera.top = 20;
    directionalLight.shadow.camera.bottom = -20;
    this.scene.add(directionalLight);
    
    const hemisphereLight = new THREE.HemisphereLight(0x87ceeb, 0x444444, 0.3);
    this.scene.add(hemisphereLight);
  }
  
  setupGrid() {
    this.gridHelper = new THREE.GridHelper(50, 50, 0x444444, 0x333333);
    this.scene.add(this.gridHelper);
    
    this.axisHelper = new THREE.AxesHelper(5);
    this.scene.add(this.axisHelper);
  }
  
  setupEventListeners() {
    window.addEventListener('resize', () => this.onWindowResize());
    this.renderer.domElement.addEventListener('click', (event) => this.onClick(event));
  }
  
  onWindowResize() {
    if (!this.container) return;
    
    const width = this.container.clientWidth;
    const height = this.container.clientHeight;
    
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
  }
  
  onClick(event) {
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
    
    const store = useStore.getState();
    const activeTool = store.activeTool;
    
    if (activeTool === 'select') {
      this.handleSelectionClick();
    } else if (['wall', 'door', 'window', 'furniture'].includes(activeTool)) {
      this.handleCreationClick(activeTool);
    }
  }
  
  handleSelectionClick() {
    const visibleMeshes = Array.from(this.meshesMap.values()).filter(m => m.visible);
    this.raycaster.setFromCamera(this.mouse, this.camera);
    const intersects = this.raycaster.intersectObjects(visibleMeshes, true);
    
    if (intersects.length > 0) {
      let selectedObject = intersects[0].object;
      while (selectedObject.parent && !this.meshesMap.has(selectedObject.uuid)) {
        selectedObject = selectedObject.parent;
      }
      
      if (this.meshesMap.has(selectedObject.uuid)) {
        this.selectMesh(selectedObject);
      }
    } else {
      this.deselectMesh();
    }
  }
  
  handleCreationClick(objectType) {
    const plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
    const intersectPoint = new THREE.Vector3();
    
    this.raycaster.setFromCamera(this.mouse, this.camera);
    this.raycaster.ray.intersectPlane(plane, intersectPoint);
    
    if (intersectPoint) {
      const store = useStore.getState();
      
      const defaultTransform = factoryRegistry.getDefaultTransform(objectType);
      
      const objectData = {
        object_type: objectType,
        transform: {
          position: {
            x: Math.round(intersectPoint.x * 2) / 2,
            y: defaultTransform.position.y,
            z: Math.round(intersectPoint.z * 2) / 2
          },
          rotation: defaultTransform.rotation,
          scale: defaultTransform.scale
        },
        material_id: 'mat_default_01',
        creator_id: store.userId
      };
      
      store.addObject(objectData);
    }
  }
  
  selectMesh(mesh) {
    this.selectedMesh = mesh;
    this.transformControls.attach(mesh);
    
    let objectId = null;
    for (const [id, meshObj] of this.meshesMap.entries()) {
      if (meshObj.uuid === mesh.uuid) {
        objectId = id;
        break;
      }
    }
    
    if (objectId) {
      useStore.getState().selectObject(objectId);
    }
  }
  
  deselectMesh() {
    this.selectedMesh = null;
    this.transformControls.detach();
    useStore.getState().clearSelection();
  }
  
  onTransformChange() {
    if (!this.selectedMesh) return;
    
    let objectId = null;
    for (const [id, mesh] of this.meshesMap.entries()) {
      if (mesh.uuid === this.selectedMesh.uuid) {
        objectId = id;
        break;
      }
    }
    
    if (objectId) {
      const store = useStore.getState();
      const existing = store.getObject(objectId);
      
      if (existing) {
        const transform = {
          position: {
            x: this.selectedMesh.position.x,
            y: this.selectedMesh.position.y,
            z: this.selectedMesh.position.z
          },
          rotation: {
            x: this.selectedMesh.rotation.x,
            y: this.selectedMesh.rotation.y,
            z: this.selectedMesh.rotation.z
          },
          scale: {
            x: this.selectedMesh.scale.x,
            y: this.selectedMesh.scale.y,
            z: this.selectedMesh.scale.z
          }
        };
        
        store.updateObject(objectId, { transform });
      }
    }
  }
  
  createMeshForObject(objectData) {
    const { object_id, object_type, transform, material_id, asset_id } = objectData;
    
    const validation = factoryRegistry.validateParams(object_type, { transform, material_id, asset_id });
    
    if (!validation.valid) {
      console.error('Invalid object parameters:', validation.errors);
      return null;
    }
    
    const mesh = factoryRegistry.createFromObjectData(objectData);
    
    if (!mesh) {
      console.error('Failed to create mesh for object:', object_id);
      return null;
    }
    
    mesh.userData.objectId = object_id;
    mesh.userData.objectType = object_type;
    mesh.userData.assetId = asset_id;
    
    this.scene.add(mesh);
    this.meshesMap.set(object_id, mesh);
    
    return mesh;
  }
  
  updateMeshTransform(objectId, transform) {
    const mesh = this.meshesMap.get(objectId);
    if (!mesh || !transform) return;
    
    factoryRegistry.updateMeshTransform(mesh, transform);
  }
  
  removeMesh(objectId) {
    const mesh = this.meshesMap.get(objectId);
    if (mesh) {
      if (this.selectedMesh && this.selectedMesh.uuid === mesh.uuid) {
        this.deselectMesh();
      }
      
      if (this.resourcePoolEnabled) {
        mesh.visible = false;
        this.invisibleMeshes.set(objectId, {
          mesh,
          timestamp: Date.now()
        });
        
        this.scene.remove(mesh);
        
        setTimeout(() => {
          this.checkResourceRelease(objectId);
        }, 10000);
      } else {
        this.scene.remove(mesh);
        this.disposeMeshResources(mesh);
      }
      
      this.meshesMap.delete(objectId);
    }
  }
  
  checkResourceRelease(objectId) {
    const entry = this.invisibleMeshes.get(objectId);
    if (!entry) return;
    
    if (Date.now() - entry.timestamp >= 10000) {
      this.disposeMeshResources(entry.mesh);
      this.invisibleMeshes.delete(objectId);
    }
  }
  
  disposeMeshResources(mesh) {
    if (!mesh) return;
    
    if (mesh.geometry) {
      if (this.resourcePoolEnabled) {
        resourcePool.releaseGeometry(mesh.geometry);
      } else {
        mesh.geometry.dispose();
      }
    }
    
    if (mesh.material) {
      if (Array.isArray(mesh.material)) {
        mesh.material.forEach(m => {
          if (this.resourcePoolEnabled) {
            resourcePool.releaseMaterial(m);
          } else {
            m.dispose();
          }
        });
      } else {
        if (this.resourcePoolEnabled) {
          resourcePool.releaseMaterial(mesh.material);
        } else {
          mesh.material.dispose();
        }
      }
    }
  }
  
  clearAllMeshes() {
    for (const objectId of this.meshesMap.keys()) {
      this.removeMesh(objectId);
    }
    this.meshesMap.clear();
    
    for (const [objectId, entry] of this.invisibleMeshes.entries()) {
      this.disposeMeshResources(entry.mesh);
    }
    this.invisibleMeshes.clear();
  }
  
  performFrustumCulling() {
    if (!this.cullingEnabled || !this.camera) {
      this.frameStats.totalObjects = this.meshesMap.size;
      this.frameStats.visibleObjects = this.meshesMap.size;
      this.frameStats.culledObjects = 0;
      return;
    }
    
    frustumCullingManager.update(this.camera);
    
    let visibleCount = 0;
    let culledCount = 0;
    
    for (const [objectId, mesh] of this.meshesMap.entries()) {
      const isVisible = frustumCullingManager.isObjectVisible(mesh, this.camera);
      
      if (isVisible !== mesh.visible) {
        mesh.visible = isVisible;
      }
      
      if (isVisible) {
        visibleCount++;
      } else {
        culledCount++;
      }
    }
    
    this.frameStats.totalObjects = this.meshesMap.size;
    this.frameStats.visibleObjects = visibleCount;
    this.frameStats.culledObjects = culledCount;
  }
  
  applyLOD() {
    if (!this.lodEnabled || !this.camera) return;
    
    for (const [objectId, mesh] of this.meshesMap.entries()) {
      if (!mesh.visible) continue;
      
      const lodLevel = frustumCullingManager.getLodLevel(mesh, this.camera);
      const visibilityLevel = frustumCullingManager.getVisibilityLevel(mesh, this.camera);
      
      if (mesh.userData.lastLOD !== lodLevel) {
        mesh.userData.lastLOD = lodLevel;
        
        if (lodLevel === 'low') {
          mesh.castShadow = false;
          mesh.receiveShadow = false;
        } else {
          const shouldRenderShadow = frustumCullingManager.shouldRenderShadow(mesh, this.camera);
          mesh.castShadow = shouldRenderShadow;
          mesh.receiveShadow = shouldRenderShadow;
        }
      }
    }
  }
  
  setTransformMode(mode) {
    if (this.transformControls) {
      this.transformControls.setMode(mode);
    }
  }
  
  setGridVisible(visible) {
    if (this.gridHelper) {
      this.gridHelper.visible = visible;
    }
  }
  
  setAxisVisible(visible) {
    if (this.axisHelper) {
      this.axisHelper.visible = visible;
    }
  }
  
  setBackgroundColor(color) {
    if (this.scene) {
      this.scene.background = new THREE.Color(color);
    }
  }
  
  toggleCulling(enabled) {
    this.cullingEnabled = enabled;
    frustumCullingManager.toggleCulling(enabled);
    
    if (!enabled) {
      for (const mesh of this.meshesMap.values()) {
        mesh.visible = true;
      }
    }
  }
  
  toggleLOD(enabled) {
    this.lodEnabled = enabled;
  }
  
  focusOnObject(objectId) {
    const mesh = this.meshesMap.get(objectId);
    if (mesh && this.controls) {
      const box = new THREE.Box3().setFromObject(mesh);
      const center = box.getCenter(new THREE.Vector3());
      
      const size = box.getSize(new THREE.Vector3());
      const maxDim = Math.max(size.x, size.y, size.z);
      const fov = this.camera.fov * (Math.PI / 180);
      const cameraZ = Math.abs(maxDim / 2 / Math.tan(fov / 2));
      
      this.camera.position.copy(center);
      this.camera.position.z += cameraZ;
      this.camera.position.y += cameraZ * 0.5;
      this.camera.lookAt(center);
      
      this.controls.target.copy(center);
      this.controls.update();
    }
  }
  
  startAnimation() {
    const animate = () => {
      this.animationFrameId = requestAnimationFrame(animate);
      
      const delta = this.clock.getDelta();
      
      if (this.controls) {
        this.controls.update();
      }
      
      this.performFrustumCulling();
      this.applyLOD();
      
      this.renderer.render(this.scene, this.camera);
    };
    
    animate();
  }
  
  getRenderStats() {
    return {
      ...this.frameStats,
      resourcePool: this.resourcePoolEnabled ? resourcePool.getStats() : null,
      cullingEnabled: this.cullingEnabled,
      lodEnabled: this.lodEnabled
    };
  }
  
  destroy() {
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
    }
    
    this.clearAllMeshes();
    
    if (this.resourcePoolEnabled) {
      resourcePool.clear();
    }
    
    if (this.transformControls) {
      this.transformControls.dispose();
    }
    
    if (this.controls) {
      this.controls.dispose();
    }
    
    if (this.renderer) {
      this.renderer.dispose();
      if (this.container && this.renderer.domElement) {
        this.container.removeChild(this.renderer.domElement);
      }
    }
    
    window.removeEventListener('resize', this.onWindowResize.bind(this));
    
    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.controls = null;
    this.transformControls = null;
    this.isInitialized = false;
    
    console.log('SceneManager destroyed');
  }
}

const sceneManager = new SceneManager();
export default sceneManager;
