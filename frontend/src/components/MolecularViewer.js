import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import {
  getElementColor, getElementRadius, getChainColor,
  getHydrophobicityColor, getBFactorColor, rmsdColor
} from '../utils/colors.js';

export class MolecularViewer {
  constructor(canvas) {
    this.canvas = canvas;
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x0a0e17);

    this.camera = new THREE.PerspectiveCamera(60, 1, 0.1, 2000);
    this.camera.position.set(40, 30, 50);

    this.renderer = new THREE.WebGLRenderer({
      canvas, antialias: true, alpha: true, preserveDrawingBuffer: true
    });
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.renderer.shadowMap.enabled = false;

    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.rotateSpeed = 0.8;
    this.controls.zoomSpeed = 1.2;

    this.ambientLight = new THREE.AmbientLight(0x404060, 1.5);
    this.scene.add(this.ambientLight);

    this.dirLight1 = new THREE.DirectionalLight(0xffffff, 1.2);
    this.dirLight1.position.set(50, 80, 60);
    this.scene.add(this.dirLight1);

    this.dirLight2 = new THREE.DirectionalLight(0x8888ff, 0.4);
    this.dirLight2.position.set(-40, -20, -60);
    this.scene.add(this.dirLight2);

    this.atomMesh = null;
    this.lodAtomGroup = null;
    this.lodMeshes = [];
    this.bondMesh = null;
    this.surfaceMesh = null;
    this.hbondMesh = null;
    this.labelSprites = [];
    this.annotationGroup = new THREE.Group();
    this.scene.add(this.annotationGroup);

    this.structureData = null;
    this.atoms = [];
    this.bonds = [];
    this.chainMap = {};
    this.renderMode = 'ball-stick';
    this.colorScheme = 'element';
    this.displayOptions = { atoms: true, bonds: true, surface: false, electrostatic: false, hbonds: false, labels: true, annotations: true };

    this.lodOptions = {
      enabled: true,
      distanceNear: 15,
      distanceMid: 50,
      forceLOD: null
    };

    this.debugDisplay = null;
    this.showLODDebug = false;

    this.raycaster = new THREE.Raycaster();
    this.mouse = new THREE.Vector2();
    this.selectedAtom = null;
    this.highlightMesh = null;
    this.measurementLines = [];

    this.onAtomClick = null;
    this.onBackgroundClick = null;

    this._resize();
    window.addEventListener('resize', () => this._resize());
    canvas.addEventListener('click', (e) => this._onClick(e));

    this._animate();
  }

  _resize() {
    const container = this.canvas.parentElement;
    const w = container.clientWidth;
    const h = container.clientHeight;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h);
  }

  _createBillboardShaderMaterial() {
    const vertexShader = `
      uniform float size;
      varying vec3 vColor;
      varying vec2 vUv;
      
      void main() {
        vColor = instanceColor.rgb;
        vUv = uv;
        
        vec4 mvPosition = modelViewMatrix * vec4(instanceMatrix[3].xyz, 1.0);
        vec3 cameraRight = vec3(modelViewMatrix[0].x, modelViewMatrix[1].x, modelViewMatrix[2].x);
        vec3 cameraUp = vec3(modelViewMatrix[0].y, modelViewMatrix[1].y, modelViewMatrix[2].y);
        
        vec3 scale = vec3(
          length(instanceMatrix[0].xyz),
          length(instanceMatrix[1].xyz),
          length(instanceMatrix[2].xyz)
        );
        
        vec3 offset = (cameraRight * position.x + cameraUp * position.y) * size * scale.x;
        mvPosition.xyz += offset;
        
        gl_Position = projectionMatrix * mvPosition;
      }
    `;

    const fragmentShader = `
      varying vec3 vColor;
      varying vec2 vUv;
      
      void main() {
        vec2 center = vUv - 0.5;
        float dist = length(center);
        
        if (dist > 0.5) {
          discard;
        }
        
        float alpha = 1.0 - smoothstep(0.4, 0.5, dist);
        float light = 1.0 - dist * 1.2;
        light = max(light, 0.3);
        
        gl_FragColor = vec4(vColor * light, alpha);
      }
    `;

    return new THREE.ShaderMaterial({
      vertexShader,
      fragmentShader,
      uniforms: {
        size: { value: 1.0 }
      },
      transparent: true,
      depthWrite: true
    });
  }

  _animate() {
    requestAnimationFrame(() => this._animate());
    this.controls.update();
    this._updateLabels();
    this._updateLOD();
    this._updateLODDebug();
    this.renderer.render(this.scene, this.camera);
  }

  _updateLOD() {
    if (!this.lodOptions.enabled || !this.lodAtomGroup) return;
    this._updateLODInstances();
  }

  _updateLODDebug() {
    if (!this.showLODDebug || !this.debugDisplay) return;
    const stats = this.getCurrentLODStats();
    this.debugDisplay.textContent = `LOD: High=${stats.highCount}, Medium=${stats.mediumCount}, Low=${stats.lowCount}, Triangles=${stats.estimatedTriangles.toLocaleString()}`;
  }

  _onClick(event) {
    const rect = this.canvas.getBoundingClientRect();
    this.mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    this.mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    this.raycaster.setFromCamera(this.mouse, this.camera);

    const meshesToTest = [];
    if (this.atomMesh) meshesToTest.push({ mesh: this.atomMesh, isLod: false });
    if (this.lodMeshes) {
      for (const mesh of this.lodMeshes) {
        if (mesh.visible && mesh.count > 0) {
          meshesToTest.push({ mesh, isLod: true });
        }
      }
    }

    for (const { mesh, isLod } of meshesToTest) {
      const intersects = this.raycaster.intersectObject(mesh);
      if (intersects.length > 0) {
        const instanceId = intersects[0].instanceId;
        
        let atomIndex = null;
        if (isLod) {
          const atomIndices = mesh.userData.atomIndices;
          if (atomIndices && instanceId < atomIndices.length) {
            atomIndex = atomIndices[instanceId];
          }
        } else {
          if (instanceId < this.atoms.length) {
            atomIndex = instanceId;
          }
        }
        
        if (atomIndex !== null) {
          const atom = this.atoms[atomIndex];
          this._highlightAtom(atomIndex);
          if (this.onAtomClick) this.onAtomClick(atom, atomIndex);
          return;
        }
      }
    }

    this._clearHighlight();
    if (this.onBackgroundClick) this.onBackgroundClick();
  }

  _highlightAtom(instanceId) {
    this._clearHighlight();
    const atom = this.atoms[instanceId];
    const sphere = new THREE.Mesh(
      new THREE.SphereGeometry(getElementRadius(atom.element) * (this.renderMode === 'spacefill' ? 1.5 : 2.0) + 0.3, 16, 12),
      new THREE.MeshBasicMaterial({ color: 0x00ff88, transparent: true, opacity: 0.4, wireframe: true })
    );
    sphere.position.set(atom.x, atom.y, atom.z);
    this.scene.add(sphere);
    this.highlightMesh = sphere;
    this.selectedAtom = atom;
  }

  _clearHighlight() {
    if (this.highlightMesh) {
      this.scene.remove(this.highlightMesh);
      this.highlightMesh.geometry.dispose();
      this.highlightMesh.material.dispose();
      this.highlightMesh = null;
    }
    this.selectedAtom = null;
  }

  loadStructure(data) {
    this.structureData = data;
    this.atoms = data.atoms || [];
    this.bonds = data.bonds || [];

    this._buildChainMap();
    this._centerStructure();
    this._buildModel();
    this._buildLabels();
    this._detectHydrogenBonds();
  }

  _buildChainMap() {
    this.chainMap = {};
    let idx = 0;
    for (const atom of this.atoms) {
      if (!(atom.chainId in this.chainMap)) {
        this.chainMap[atom.chainId] = idx++;
      }
    }
  }

  _centerStructure() {
    if (this.atoms.length === 0) return;
    let cx = 0, cy = 0, cz = 0;
    for (const a of this.atoms) { cx += a.x; cy += a.y; cz += a.z; }
    cx /= this.atoms.length; cy /= this.atoms.length; cz /= this.atoms.length;
    for (const a of this.atoms) { a.x -= cx; a.y -= cy; a.z -= cz; }
  }

  _buildModel() {
    this._clearModel();
    if (this.displayOptions.atoms) {
      if (this.lodOptions.enabled) {
        this._buildLODAtomMeshes();
      } else {
        this._buildAtomMesh();
      }
    }
    if (this.displayOptions.bonds) this._buildBondMesh();
  }

  _clearModel() {
    if (this.atomMesh) { this.scene.remove(this.atomMesh); this.atomMesh.geometry.dispose(); this.atomMesh.material.dispose(); this.atomMesh = null; }
    if (this.lodAtomGroup) { 
      this.scene.remove(this.lodAtomGroup); 
      for (const mesh of this.lodMeshes) {
        if (mesh.geometry) mesh.geometry.dispose();
        if (mesh.material) {
          if (Array.isArray(mesh.material)) {
            mesh.material.forEach(m => m.dispose());
          } else {
            mesh.material.dispose();
          }
        }
      }
      this.lodAtomGroup = null; 
    }
    this.lodMeshes = [];
    this.atomMatrices = [];
    this.atomColors = [];
    this.atomRadii = [];
    if (this.bondMesh) { this.scene.remove(this.bondMesh); this.bondMesh.geometry.dispose(); this.bondMesh.material.dispose(); this.bondMesh = null; }
    for (const line of this.measurementLines) { this.scene.remove(line); line.geometry.dispose(); line.material.dispose(); }
    this.measurementLines = [];
  }

  _buildLODAtomMeshes() {
    const count = this.atoms.length;
    if (count === 0) return;

    let baseRadius = 0.3;
    let radiusScale = 1.0;
    if (this.renderMode === 'spacefill') { baseRadius = 0; radiusScale = 1.5; }
    else if (this.renderMode === 'wireframe') { baseRadius = 0.12; radiusScale = 0.3; }

    this.lodAtomGroup = new THREE.Group();
    this.lodMeshes = [];
    this.atomRadii = [];

    const highGeom = new THREE.SphereGeometry(1.0, 32, 32);
    const medGeom = new THREE.SphereGeometry(1.0, 16, 16);
    const lowGeom = new THREE.PlaneGeometry(2.0, 2.0);

    const phongMaterial = new THREE.MeshPhongMaterial({ vertexColors: true, shininess: 60 });
    const billboardMaterial = this._createBillboardShaderMaterial();

    const highMesh = new THREE.InstancedMesh(highGeom, phongMaterial, count);
    const medMesh = new THREE.InstancedMesh(medGeom, phongMaterial, count);
    const lowMesh = new THREE.InstancedMesh(lowGeom, billboardMaterial, count);

    highMesh.frustumCulled = false;
    medMesh.frustumCulled = false;
    lowMesh.frustumCulled = false;

    this.atomMatrices = [];
    this.atomColors = [];
    const dummy = new THREE.Object3D();
    const color = new THREE.Color();
    const instanceScales = new Float32Array(count);

    for (let i = 0; i < count; i++) {
      const atom = this.atoms[i];
      const r = (baseRadius + getElementRadius(atom.element) * radiusScale) * 0.5;
      this.atomRadii.push(r);
      instanceScales[i] = r;
      dummy.position.set(atom.x, atom.y, atom.z);
      dummy.scale.set(r, r, r);
      dummy.updateMatrix();
      
      this.atomMatrices.push(dummy.matrix.clone());
      color.setHex(this._getAtomColor(atom, i));
      this.atomColors.push(color.clone());
    }

    lowMesh.geometry.setAttribute('instanceScale', new THREE.InstancedBufferAttribute(instanceScales, 1));
    highMesh.userData.atomCount = count;
    medMesh.userData.atomCount = count;
    lowMesh.userData.atomCount = count;

    this.lodAtomGroup.add(highMesh);
    this.lodAtomGroup.add(medMesh);
    this.lodAtomGroup.add(lowMesh);
    this.lodMeshes = [highMesh, medMesh, lowMesh];

    this.scene.add(this.lodAtomGroup);
    this._updateLODInstances();
  }

  _updateLODInstances() {
    if (!this.lodOptions.enabled || !this.lodAtomGroup) return;

    const count = this.atoms.length;
    const cameraPos = this.camera.position;
    const { distanceNear, distanceMid, forceLOD } = this.lodOptions;

    const highIndices = [];
    const medIndices = [];
    const lowIndices = [];

    for (let i = 0; i < count; i++) {
      const atom = this.atoms[i];
      const dist = Math.sqrt(
        (atom.x - cameraPos.x) ** 2 + 
        (atom.y - cameraPos.y) ** 2 + 
        (atom.z - cameraPos.z) ** 2
      );

      let lodLevel;
      if (forceLOD !== null) {
        lodLevel = forceLOD;
      } else if (dist < distanceNear) {
        lodLevel = 0;
      } else if (dist < distanceMid) {
        lodLevel = 1;
      } else {
        lodLevel = 2;
      }

      if (lodLevel === 0) highIndices.push(i);
      else if (lodLevel === 1) medIndices.push(i);
      else lowIndices.push(i);
    }

    this._updateMeshInstances(this.lodMeshes[0], highIndices);
    this._updateMeshInstances(this.lodMeshes[1], medIndices);
    this._updateMeshInstances(this.lodMeshes[2], lowIndices);

    this.lodMeshes[0].userData.atomIndices = highIndices;
    this.lodMeshes[1].userData.atomIndices = medIndices;
    this.lodMeshes[2].userData.atomIndices = lowIndices;

    this._lastLODStats = {
      highCount: highIndices.length,
      mediumCount: medIndices.length,
      lowCount: lowIndices.length
    };
  }

  _updateMeshInstances(mesh, indices) {
    const count = indices.length;
    mesh.count = count;

    for (let i = 0; i < count; i++) {
      const atomIdx = indices[i];
      mesh.setMatrixAt(i, this.atomMatrices[atomIdx]);
      mesh.setColorAt(i, this.atomColors[atomIdx]);
    }

    mesh.instanceMatrix.needsUpdate = true;
    mesh.instanceColor.needsUpdate = true;
  }

  _buildAtomMesh() {
    const count = this.atoms.length;
    if (count === 0) return;

    let baseRadius = 0.3;
    let radiusScale = 1.0;
    if (this.renderMode === 'spacefill') { baseRadius = 0; radiusScale = 1.5; }
    else if (this.renderMode === 'wireframe') { baseRadius = 0.12; radiusScale = 0.3; }

    const geometry = new THREE.SphereGeometry(1, 14, 10);
    const material = new THREE.MeshPhongMaterial({ vertexColors: true, shininess: 60 });

    this.atomMesh = new THREE.InstancedMesh(geometry, material, count);

    const dummy = new THREE.Object3D();
    const color = new THREE.Color();

    for (let i = 0; i < count; i++) {
      const atom = this.atoms[i];
      const r = (baseRadius + getElementRadius(atom.element) * radiusScale) * 0.5;
      dummy.position.set(atom.x, atom.y, atom.z);
      dummy.scale.set(r, r, r);
      dummy.updateMatrix();
      this.atomMesh.setMatrixAt(i, dummy.matrix);

      color.setHex(this._getAtomColor(atom, i));
      this.atomMesh.setColorAt(i, color);
    }

    this.atomMesh.instanceMatrix.needsUpdate = true;
    this.atomMesh.instanceColor.needsUpdate = true;
    this.atomMesh.userData.atomCount = count;
    this.scene.add(this.atomMesh);
  }

  _buildBondMesh() {
    if (this.bonds.length === 0) {
      this._buildDistanceBonds();
      return;
    }

    const bondPairs = [];
    const atomMap = {};
    for (const atom of this.atoms) { atomMap[atom.serialNumber] = atom; }

    for (const bond of this.bonds) {
      const a1 = atomMap[bond.atomSerial];
      if (!a1) continue;
      for (const bs of bond.bondedAtoms) {
        const a2 = atomMap[bs];
        if (!a2) continue;
        bondPairs.push([a1, a2]);
      }
    }

    this._createBondInstances(bondPairs);
  }

  _buildDistanceBonds() {
    const maxDist = 1.9;
    const bondPairs = [];
    const atomMap = {};
    for (const atom of this.atoms) { atomMap[atom.serialNumber] = atom; }

    const backbone = this.atoms.filter(a => !a.isHetatm && (a.atomName === 'N' || a.atomName === 'CA' || a.atomName === 'C'));

    for (let i = 0; i < backbone.length - 1; i++) {
      const a1 = backbone[i];
      const a2 = backbone[i + 1];
      if (a1.chainId !== a2.chainId) continue;
      const dist = Math.sqrt((a1.x - a2.x) ** 2 + (a1.y - a2.y) ** 2 + (a1.z - a2.z) ** 2);
      if (dist < maxDist * 1.5) {
        bondPairs.push([a1, a2]);
      }
    }

    for (let i = 0; i < this.atoms.length; i++) {
      const a1 = this.atoms[i];
      if (a1.isHetatm) continue;
      for (let j = i + 1; j < this.atoms.length; j++) {
        const a2 = this.atoms[j];
        if (a2.isHetatm) continue;
        if (a1.chainId !== a2.chainId) continue;
        if (a1.residueSeqNumber !== a2.residueSeqNumber) continue;
        const dist = Math.sqrt((a1.x - a2.x) ** 2 + (a1.y - a2.y) ** 2 + (a1.z - a2.z) ** 2);
        if (dist < maxDist) {
          bondPairs.push([a1, a2]);
        }
      }
    }

    this._createBondInstances(bondPairs);
  }

  _createBondInstances(bondPairs) {
    if (bondPairs.length === 0) return;

    let bondRadius = 0.08;
    if (this.renderMode === 'wireframe') bondRadius = 0.04;

    const geometry = new THREE.CylinderGeometry(1, 1, 1, 6, 1);
    const material = new THREE.MeshPhongMaterial({ vertexColors: true, shininess: 40 });
    this.bondMesh = new THREE.InstancedMesh(geometry, material, bondPairs.length);

    const dummy = new THREE.Object3D();
    const color = new THREE.Color();
    const direction = new THREE.Vector3();
    const midpoint = new THREE.Vector3();
    const yAxis = new THREE.Vector3(0, 1, 0);
    const quaternion = new THREE.Quaternion();

    for (let i = 0; i < bondPairs.length; i++) {
      const [a1, a2] = bondPairs[i];
      direction.set(a2.x - a1.x, a2.y - a1.y, a2.z - a1.z);
      const length = direction.length();
      direction.normalize();
      midpoint.set((a1.x + a2.x) / 2, (a1.y + a2.y) / 2, (a1.z + a2.z) / 2);

      quaternion.setFromUnitVectors(yAxis, direction);
      dummy.position.copy(midpoint);
      dummy.quaternion.copy(quaternion);
      dummy.scale.set(bondRadius, length, bondRadius);
      dummy.updateMatrix();
      this.bondMesh.setMatrixAt(i, dummy.matrix);

      color.setHex(0x606060);
      this.bondMesh.setColorAt(i, color);
    }

    this.bondMesh.instanceMatrix.needsUpdate = true;
    this.bondMesh.instanceColor.needsUpdate = true;
    this.scene.add(this.bondMesh);
  }

  _buildLabels() {
    for (const s of this.labelSprites) { this.scene.remove(s); s.material.map.dispose(); s.material.dispose(); }
    this.labelSprites = [];

    if (!this.displayOptions.labels) return;

    const seen = new Set();
    const canvas2d = document.createElement('canvas');
    canvas2d.width = 256; canvas2d.height = 64;
    const ctx = canvas2d.getContext('2d');

    for (const atom of this.atoms) {
      if (atom.atomName !== 'CA') continue;
      const key = `${atom.chainId}:${atom.residueSeqNumber}`;
      if (seen.has(key)) continue;
      seen.add(key);

      const label = `${atom.residueName}${atom.residueSeqNumber}`;
      ctx.clearRect(0, 0, 256, 64);
      ctx.font = 'bold 28px Arial';
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.fillText(label, 128, 40);

      const texture = new THREE.CanvasTexture(canvas2d.cloneNode(true));
      const texCtx = texture.image.getContext('2d');
      texCtx.clearRect(0, 0, 256, 64);
      texCtx.font = 'bold 28px Arial';
      texCtx.fillStyle = '#ffffff';
      texCtx.textAlign = 'center';
      texCtx.fillText(label, 128, 40);
      texture.needsUpdate = true;

      const spriteMat = new THREE.SpriteMaterial({ map: texture, transparent: true, opacity: 0.8, depthTest: false });
      const sprite = new THREE.Sprite(spriteMat);
      sprite.position.set(atom.x, atom.y + 1.5, atom.z);
      sprite.scale.set(4, 1, 1);
      sprite.userData.residueKey = key;
      this.scene.add(sprite);
      this.labelSprites.push(sprite);
    }
  }

  _updateLabels() {
    for (const sprite of this.labelSprites) {
      const dist = this.camera.position.distanceTo(sprite.position);
      const scale = Math.max(2, dist * 0.08);
      sprite.scale.set(scale * 4, scale, 1);
    }
  }

  _detectHydrogenBonds() {
    this.hbondData = [];
    const donors = this.atoms.filter(a =>
      !a.isHetatm && (a.atomName === 'N' || a.atomName === 'NE' || a.atomName === 'NH1' || a.atomName === 'NH2' || a.atomName === 'NZ' || a.atomName === 'ND1' || a.atomName === 'NE2')
    );
    const acceptors = this.atoms.filter(a =>
      !a.isHetatm && (a.atomName === 'O' || a.atomName === 'OD1' || a.atomName === 'OD2' || a.atomName === 'OE1' || a.atomName === 'OE2' || a.atomName === 'OG' || a.atomName === 'OG1' || a.atomName === 'OH')
    );

    for (const d of donors) {
      for (const a of acceptors) {
        if (d.chainId === a.chainId && Math.abs(d.residueSeqNumber - a.residueSeqNumber) < 2) continue;
        const dist = Math.sqrt((d.x - a.x) ** 2 + (d.y - a.y) ** 2 + (d.z - a.z) ** 2);
        if (dist < 3.5) {
          this.hbondData.push({ donor: d, acceptor: a, distance: dist });
        }
      }
    }
  }

  showHydrogenBonds(show) {
    if (this.hbondMesh) { this.scene.remove(this.hbondMesh); this.hbondMesh.geometry.dispose(); this.hbondMesh.material.dispose(); this.hbondMesh = null; }
    if (!show || this.hbondData.length === 0) return;

    const positions = [];
    for (const hb of this.hbondData) {
      positions.push(hb.donor.x, hb.donor.y, hb.donor.z);
      positions.push(hb.acceptor.x, hb.acceptor.y, hb.acceptor.z);
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    const material = new THREE.LineDashedMaterial({ color: 0x00ffff, dashSize: 0.3, gapSize: 0.15, linewidth: 1, transparent: true, opacity: 0.6 });
    this.hbondMesh = new THREE.LineSegments(geometry, material);
    this.hbondMesh.computeLineDistances();
    this.scene.add(this.hbondMesh);
  }

  loadSurface(surfaceData) {
    if (this.surfaceMesh) { this.scene.remove(this.surfaceMesh); this.surfaceMesh.geometry.dispose(); this.surfaceMesh.material.dispose(); this.surfaceMesh = null; }

    const vertices = new Float32Array(surfaceData.vertices);
    const indices = new Uint32Array(surfaceData.indices);
    const potentials = surfaceData.potentials;
    const minP = surfaceData.minPotential;
    const maxP = surfaceData.maxPotential;

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(vertices, 3));
    geometry.setIndex(new THREE.BufferAttribute(indices, 1));

    const colors = new Float32Array((vertices.length / 3) * 3);
    for (let i = 0; i < vertices.length / 3; i++) {
      const t = (potentials[i] - minP) / (maxP - minP + 0.001);
      if (t < 0.5) {
        const s = t * 2;
        colors[i * 3] = 1 - s;
        colors[i * 3 + 1] = 0.2;
        colors[i * 3 + 2] = s;
      } else {
        const s = (t - 0.5) * 2;
        colors[i * 3] = s;
        colors[i * 3 + 1] = 0.2;
        colors[i * 3 + 2] = 1 - s;
      }
    }
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    geometry.computeVertexNormals();

    const material = new THREE.MeshPhongMaterial({
      vertexColors: true, transparent: true, opacity: 0.6,
      side: THREE.DoubleSide, shininess: 30
    });

    this.surfaceMesh = new THREE.Mesh(geometry, material);
    this.scene.add(this.surfaceMesh);
  }

  showRmsdColoring(perResidueRmsd) {
    const color = new THREE.Color();
    const residueMap = {};
    for (const r of perResidueRmsd) {
      residueMap[`${r.chainId}:${r.resSeq}`] = r.rmsd;
    }

    if (this.atomMesh) {
      for (let i = 0; i < this.atoms.length; i++) {
        const atom = this.atoms[i];
        const key = `${atom.chainId}:${atom.residueSeqNumber}`;
        const rmsd = residueMap[key] || 0;
        color.setHex(rmsdColor(rmsd));
        this.atomMesh.setColorAt(i, color);
      }
      this.atomMesh.instanceColor.needsUpdate = true;
    }
    
    if (this.lodAtomGroup && this.atomColors) {
      for (let i = 0; i < this.atoms.length; i++) {
        const atom = this.atoms[i];
        const key = `${atom.chainId}:${atom.residueSeqNumber}`;
        const rmsd = residueMap[key] || 0;
        color.setHex(rmsdColor(rmsd));
        this.atomColors[i].copy(color);
      }
      this._updateLODInstances();
    }

    const legend = document.getElementById('rmsd-legend');
    if (legend) legend.style.display = 'block';
  }

  addDistanceLine(atom1, atom2) {
    const points = [
      new THREE.Vector3(atom1.x, atom1.y, atom1.z),
      new THREE.Vector3(atom2.x, atom2.y, atom2.z)
    ];
    const geometry = new THREE.BufferGeometry().setFromPoints(points);
    const material = new THREE.LineBasicMaterial({ color: 0xffff00, linewidth: 2 });
    const line = new THREE.Line(geometry, material);
    this.scene.add(line);
    this.measurementLines.push(line);
  }

  addAngleArc(atom1, atom2, atom3) {
    this.addDistanceLine(atom1, atom2);
    this.addDistanceLine(atom2, atom3);
  }

  addAnnotationMarker(annotation) {
    const geometry = new THREE.SphereGeometry(0.6, 12, 8);
    const color = new THREE.Color(annotation.color || '#FFD700');
    const material = new THREE.MeshPhongMaterial({ color, transparent: true, opacity: 0.7 });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(annotation.positionX, annotation.positionY, annotation.positionZ);
    mesh.userData.annotationId = annotation.id;
    this.annotationGroup.add(mesh);

    const canvas2d = document.createElement('canvas');
    canvas2d.width = 256; canvas2d.height = 64;
    const ctx = canvas2d.getContext('2d');
    ctx.font = 'bold 22px Arial';
    ctx.fillStyle = '#FFD700';
    ctx.textAlign = 'center';
    ctx.fillText(annotation.label, 128, 40);
    const texture = new THREE.CanvasTexture(canvas2d);
    const spriteMat = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false });
    const sprite = new THREE.Sprite(spriteMat);
    sprite.position.set(annotation.positionX, annotation.positionY + 1.2, annotation.positionZ);
    sprite.scale.set(3, 0.75, 1);
    sprite.userData.annotationId = annotation.id;
    this.annotationGroup.add(sprite);
  }

  clearAnnotations() {
    while (this.annotationGroup.children.length > 0) {
      const child = this.annotationGroup.children[0];
      this.annotationGroup.remove(child);
      if (child.geometry) child.geometry.dispose();
      if (child.material) {
        if (child.material.map) child.material.map.dispose();
        child.material.dispose();
      }
    }
  }

  addCommentMarker(x, y, z, id) {
    const geometry = new THREE.SphereGeometry(0.4, 10, 8);
    const material = new THREE.MeshPhongMaterial({ color: 0xf59e0b, emissive: 0xf59e0b, emissiveIntensity: 0.3 });
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(x, y, z);
    mesh.userData.commentId = id;
    this.annotationGroup.add(mesh);
  }

  _getAtomColor(atom, index) {
    switch (this.colorScheme) {
      case 'chain': return getChainColor(this.chainMap[atom.chainId] || 0);
      case 'residue': {
        const hue = (atom.residueSeqNumber * 17) % 360;
        return new THREE.Color().setHSL(hue / 360, 0.7, 0.5).getHex();
      }
      case 'bfactor': {
        const bfactors = this.atoms.map(a => a.tempFactor);
        const minB = Math.min(...bfactors);
        const maxB = Math.max(...bfactors);
        return getBFactorColor(atom.tempFactor, minB, maxB);
      }
      case 'hydrophobicity': return getHydrophobicityColor(atom.residueName);
      default: return getElementColor(atom.element);
    }
  }

  setRenderMode(mode) {
    this.renderMode = mode;
    if (this.structureData) this._buildModel();
  }

  setColorScheme(scheme) {
    this.colorScheme = scheme;
    const color = new THREE.Color();
    
    if (this.atomMesh) {
      for (let i = 0; i < this.atoms.length; i++) {
        color.setHex(this._getAtomColor(this.atoms[i], i));
        this.atomMesh.setColorAt(i, color);
      }
      this.atomMesh.instanceColor.needsUpdate = true;
    }
    
    if (this.lodAtomGroup && this.atomColors) {
      for (let i = 0; i < this.atoms.length; i++) {
        color.setHex(this._getAtomColor(this.atoms[i], i));
        this.atomColors[i].copy(color);
      }
      this._updateLODInstances();
    }
  }

  setLODEnabled(enabled) {
    if (this.lodOptions.enabled === enabled) return;
    this.lodOptions.enabled = enabled;
    if (this.structureData) this._buildModel();
  }

  setLODDistances(near, mid) {
    this.lodOptions.distanceNear = near;
    this.lodOptions.distanceMid = mid;
    if (this.lodAtomGroup) {
      this._updateLODInstances();
    }
  }

  getCurrentLODStats() {
    if (!this._lastLODStats) {
      return {
        highCount: 0,
        mediumCount: 0,
        lowCount: 0,
        estimatedTriangles: 0
      };
    }
    
    const trianglesHigh = this._lastLODStats.highCount * 32 * 32 * 2;
    const trianglesMed = this._lastLODStats.mediumCount * 16 * 16 * 2;
    const trianglesLow = this._lastLODStats.lowCount * 2;
    
    return {
      highCount: this._lastLODStats.highCount,
      mediumCount: this._lastLODStats.mediumCount,
      lowCount: this._lastLODStats.lowCount,
      estimatedTriangles: trianglesHigh + trianglesMed + trianglesLow
    };
  }

  setForceLOD(level) {
    this.lodOptions.forceLOD = level;
    if (this.lodAtomGroup) {
      this._updateLODInstances();
    }
  }

  showLODDebug(show) {
    this.showLODDebug = show;
    if (show && !this.debugDisplay) {
      this.debugDisplay = document.createElement('div');
      this.debugDisplay.style.cssText = `
        position: absolute;
        top: 10px;
        left: 10px;
        background: rgba(0, 0, 0, 0.7);
        color: #00ff88;
        padding: 8px 12px;
        border-radius: 4px;
        font-family: monospace;
        font-size: 12px;
        pointer-events: none;
        z-index: 1000;
      `;
      this.canvas.parentElement.appendChild(this.debugDisplay);
    } else if (!show && this.debugDisplay) {
      this.debugDisplay.remove();
      this.debugDisplay = null;
    }
  }

  toggleDisplay(option, value) {
    this.displayOptions[option] = value;
    switch (option) {
      case 'atoms': case 'bonds': this._buildModel(); break;
      case 'surface': case 'electrostatic':
        if (this.surfaceMesh) this.surfaceMesh.visible = value;
        break;
      case 'hbonds': this.showHydrogenBonds(value); break;
      case 'labels':
        for (const s of this.labelSprites) s.visible = value;
        break;
      case 'annotations':
        this.annotationGroup.visible = value;
        break;
    }
  }

  resetCamera() {
    this.camera.position.set(40, 30, 50);
    this.controls.target.set(0, 0, 0);
    this.controls.update();
  }

  centerView() {
    if (this.atoms.length === 0) return;
    let cx = 0, cy = 0, cz = 0;
    for (const a of this.atoms) { cx += a.x; cy += a.y; cz += a.z; }
    cx /= this.atoms.length; cy /= this.atoms.length; cz /= this.atoms.length;
    this.controls.target.set(cx, cy, cz);
    this.controls.update();
  }

  toggleAutoRotate() {
    this.controls.autoRotate = !this.controls.autoRotate;
    this.controls.autoRotateSpeed = 2.0;
  }

  getCameraState() {
    return {
      positionX: this.camera.position.x,
      positionY: this.camera.position.y,
      positionZ: this.camera.position.z,
      targetX: this.controls.target.x,
      targetY: this.controls.target.y,
      targetZ: this.controls.target.z,
      upX: this.camera.up.x,
      upY: this.camera.up.y,
      upZ: this.camera.up.z,
    };
  }

  setCameraState(state) {
    this.camera.position.set(state.positionX, state.positionY, state.positionZ);
    this.controls.target.set(state.targetX, state.targetY, state.targetZ);
    this.camera.up.set(state.upX, state.upY, state.upZ);
    this.controls.update();
  }

  captureScreenshot() {
    this.renderer.render(this.scene, this.camera);
    return this.canvas.toDataURL('image/png');
  }

  getWorldPositionFromScreen(screenX, screenY) {
    const ndc = new THREE.Vector2(
      (screenX / this.canvas.clientWidth) * 2 - 1,
      -(screenY / this.canvas.clientHeight) * 2 + 1
    );
    this.raycaster.setFromCamera(ndc, this.camera);
    const plane = new THREE.Plane(new THREE.Vector3(0, 0, 1).applyQuaternion(this.camera.quaternion), 0);
    const target = new THREE.Vector3();
    this.raycaster.ray.intersectPlane(plane, target);
    return target;
  }
}
