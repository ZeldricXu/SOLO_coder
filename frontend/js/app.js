class TrafficVizApp {
    constructor() {
        this.viewer = null;
        this.heatmapImageryProvider = null;
        this.heatmapLayer = null;
        this.heatmapPrevLayer = null;
        this.heatmapNextLayer = null;
        this.buildingsDataSource = null;
        this.roadsDataSource = null;
        this.poisDataSource = null;
        this.sensorsDataSource = null;
        this.odDataSource = null;
        this.congestionDataSource = null;
        this.propagationStreamlines = [];
        this.propagationDataSource = null;
        this.propagationParticleSystem = null;

        this.FRAMES_PER_DAY = 288;
        this.FRAME_INTERVAL_MINUTES = 5;
        this.isPlaying = false;
        this.playbackSpeed = 1;
        this.currentFrameIndex = 0;
        this.playbackBaseDate = null;
        this.currentExactTime = null;
        this.playbackInterval = null;
        this.alphaBlendTimer = null;

        this.dataType = 'vehicle';
        this.vehicleType = 'all';
        this.roadLevel = 'all';
        this.direction = 'both';
        this.dimensions = null;

        this.centerLng = 116.4074;
        this.centerLat = 39.9042;

        this.init();
    }

    init() {
        this.initCesium();
        this.bindEvents();
        this.loadInitialData();
        this.initTemporalPlayback();
    }

    initTemporalPlayback() {
        const now = new Date();
        this.playbackBaseDate = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0);
        this.currentFrameIndex = Math.floor((now.getHours() * 60 + now.getMinutes()) / this.FRAME_INTERVAL_MINUTES);
        this.currentExactTime = new Date(this.playbackBaseDate.getTime() + this.currentFrameIndex * this.FRAME_INTERVAL_MINUTES * 60 * 1000);
        document.getElementById('totalFrames').textContent = this.FRAMES_PER_DAY;
        this.updateTimeDisplay();
    }

    initCesium() {
        Cesium.Ion.defaultAccessToken = '';

        this.viewer = new Cesium.Viewer('cesiumContainer', {
            animation: false,
            timeline: false,
            baseLayerPicker: false,
            geocoder: false,
            homeButton: false,
            sceneModePicker: false,
            navigationHelpButton: false,
            fullscreenButton: false,
            vrButton: false,
            infoBox: true,
            selectionIndicator: true,
            imageryProvider: new Cesium.OpenStreetMapImageryProvider({
                url: 'https://{s}.tile.openstreetmap.org/'
            }),
            terrain: Cesium.Terrain.fromWorldTerrain(),
        });

        this.viewer.scene.globe.enableLighting = true;
        this.viewer.scene.skyAtmosphere.show = true;
        this.viewer.scene.fog.enabled = true;
        this.viewer.scene.fog.density = 0.0002;

        this.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromDegrees(
                this.centerLng,
                this.centerLat,
                2000
            ),
            orientation: {
                heading: Cesium.Math.toRadians(0),
                pitch: Cesium.Math.toRadians(-45),
                roll: 0.0
            },
            duration: 2
        });

        this.initHeatmapLayer();
        this.initBuildingsLayer();
        this.initRoadsLayer();
        this.initPOIsLayer();
        this.initSensorsLayer();

        setTimeout(() => {
            document.getElementById('loadingOverlay').classList.add('hidden');
        }, 1500);
    }

    initHeatmapLayer() {
        this.removeAllHeatmapLayers();

        const params = this.buildDimensionParams();
        const currentFrameDt = this.getCurrentFrameDateTime();

        const baseUrl = `/api/v1/heatmap/temporal/frame/{z}/{x}/{y}.png?frame_time=${encodeURIComponent(currentFrameDt.toISOString())}&${params}`;

        this.heatmapImageryProvider = new Cesium.UrlTemplateImageryProvider({
            url: baseUrl,
            maximumLevel: 18,
            minimumLevel: 8,
            tileWidth: 256,
            tileHeight: 256,
        });

        this.heatmapLayer = this.viewer.imageryLayers.addImageryProvider(
            this.heatmapImageryProvider
        );
        this.heatmapLayer.alpha = 0.65;
    }

    removeAllHeatmapLayers() {
        for (const layer of [this.heatmapLayer, this.heatmapPrevLayer, this.heatmapNextLayer]) {
            if (layer) {
                try { this.viewer.imageryLayers.remove(layer); } catch (e) {}
            }
        }
        this.heatmapLayer = null;
        this.heatmapPrevLayer = null;
        this.heatmapNextLayer = null;
    }

    buildDimensionParams() {
        return `data_type=${this.dataType}&vehicle_type=${this.vehicleType}&road_level=${this.roadLevel}&direction=${this.direction}`;
    }

    getCurrentFrameDateTime() {
        return new Date(this.playbackBaseDate.getTime() + this.currentFrameIndex * this.FRAME_INTERVAL_MINUTES * 60 * 1000);
    }

    getFrameDateTime(index) {
        return new Date(this.playbackBaseDate.getTime() + index * this.FRAME_INTERVAL_MINUTES * 60 * 1000);
    }

    async initBuildingsLayer() {
        try {
            const response = await fetch('/api/v1/data/buildings?limit=200');
            const data = await response.json();

            this.buildingsDataSource = new Cesium.GeoJsonDataSource('buildings');
            await this.buildingsDataSource.load(this.buildingsToGeoJson(data.buildings));

            this.buildingsDataSource.entities.values.forEach(entity => {
                const height = entity.properties.height ? entity.properties.height.getValue() : 10;
                entity.polygon.extrudedHeight = height;
                entity.polygon.material = Cesium.Color.fromCssColorString('rgba(100, 150, 200, 0.6)');
                entity.polygon.outline = true;
                entity.polygon.outlineColor = Cesium.Color.fromCssColorString('rgba(150, 200, 255, 0.8)');
                entity.polygon.outlineWidth = 1;
            });

            this.viewer.dataSources.add(this.buildingsDataSource);
        } catch (e) {
            console.error('Failed to load buildings:', e);
        }
    }

    buildingsToGeoJson(buildings) {
        const features = buildings.map(b => ({
            type: 'Feature',
            geometry: {
                type: 'Polygon',
                coordinates: this.generateBuildingPolygon(b)
            },
            properties: {
                id: b.id,
                name: b.name,
                height: b.height,
                floors: b.floors,
                building_type: b.building_type
            }
        }));

        return {
            type: 'FeatureCollection',
            features: features
        };
    }

    generatePolygonFromCenter(lng, lat, width, height) {
        const dx = width / 2 / 111000;
        const dy = height / 2 / 111000;

        return [[
            [lng - dx, lat - dy],
            [lng + dx, lat - dy],
            [lng + dx, lat + dy],
            [lng - dx, lat + dy],
            [lng - dx, lat - dy]
        ]];
    }

    generateBuildingPolygon(building) {
        const width = 30 + Math.random() * 50;
        const height = 30 + Math.random() * 50;
        const lng = this.centerLng + (Math.random() - 0.5) * 0.04;
        const lat = this.centerLat + (Math.random() - 0.5) * 0.04;
        return this.generatePolygonFromCenter(lng, lat, width, height);
    }

    async initRoadsLayer() {
        try {
            const response = await fetch('/api/v1/data/roads?limit=100');
            const data = await response.json();

            this.roadsDataSource = new Cesium.GeoJsonDataSource('roads');
            await this.roadsDataSource.load(this.roadsToGeoJson(data.roads));

            this.roadsDataSource.entities.values.forEach(entity => {
                entity.polyline.width = 2;
                entity.polyline.material = Cesium.Color.fromCssColorString('rgba(255, 255, 255, 0.3)');
                entity.polyline.clampToGround = true;
            });

            this.viewer.dataSources.add(this.roadsDataSource);
        } catch (e) {
            console.error('Failed to load roads:', e);
            this.createDemoRoads();
        }
    }

    roadsToGeoJson(roads) {
        const features = [];

        for (let i = 0; i < 50; i++) {
            const angle = (i / 50) * Math.PI * 2;
            const length = 0.01 + Math.random() * 0.03;

            const startLng = this.centerLng + Math.cos(angle) * 0.005;
            const startLat = this.centerLat + Math.sin(angle) * 0.005;
            const endLng = this.centerLng + Math.cos(angle) * length;
            const endLat = this.centerLat + Math.sin(angle) * length;

            features.push({
                type: 'Feature',
                geometry: {
                    type: 'LineString',
                    coordinates: [[startLng, startLat], [endLng, endLat]]
                },
                properties: { id: i, type: 'road' }
            });
        }

        return { type: 'FeatureCollection', features };
    }

    createDemoRoads() {
        const features = [];

        for (let i = 0; i < 50; i++) {
            const angle = (i / 50) * Math.PI * 2;
            const length = 0.01 + Math.random() * 0.03;

            const startLng = this.centerLng + Math.cos(angle) * 0.005;
            const startLat = this.centerLat + Math.sin(angle) * 0.005;
            const endLng = this.centerLng + Math.cos(angle) * length;
            const endLat = this.centerLat + Math.sin(angle) * length;

            features.push({
                type: 'Feature',
                geometry: {
                    type: 'LineString',
                    coordinates: [[startLng, startLat], [endLng, endLat]]
                },
                properties: { id: i }
            });
        }

        this.roadsDataSource = new Cesium.GeoJsonDataSource('roads');
        this.roadsDataSource.load({ type: 'FeatureCollection', features }).then(() => {
            this.roadsDataSource.entities.values.forEach(entity => {
                entity.polyline.width = 2;
                entity.polyline.material = Cesium.Color.fromCssColorString('rgba(255, 255, 255, 0.3)');
            });
            this.viewer.dataSources.add(this.roadsDataSource);
        });
    }

    async initPOIsLayer() {
        try {
            const response = await fetch('/api/v1/data/pois?limit=100');
            const data = await response.json();

            this.poisDataSource = new Cesium.GeoJsonDataSource('pois');
            await this.poisDataSource.load(this.poisToGeoJson(data.pois));

            this.poisDataSource.entities.values.forEach(entity => {
                entity.billboard = {
                    image: this.createPOIIcon(entity.properties.category ? entity.properties.category.getValue() : 'default'),
                    width: 24,
                    height: 24,
                    verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
                };
                entity.label = {
                    text: entity.properties.name ? entity.properties.name.getValue() : '',
                    font: '12px sans-serif',
                    fillColor: Cesium.Color.WHITE,
                    outlineColor: Cesium.Color.BLACK,
                    outlineWidth: 2,
                    style: Cesium.LabelStyle.FILL_AND_OUTLINE,
                    verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
                    pixelOffset: new Cesium.Cartesian2(0, -28),
                };
            });

            this.viewer.dataSources.add(this.poisDataSource);
        } catch (e) {
            console.error('Failed to load POIs:', e);
        }
    }

    poisToGeoJson(pois) {
        const features = [];
        const categories = ['restaurant', 'shopping', 'hospital', 'school', 'park', 'station'];

        for (let i = 0; i < 50; i++) {
            const lng = this.centerLng + (Math.random() - 0.5) * 0.04;
            const lat = this.centerLat + (Math.random() - 0.5) * 0.04;
            const category = categories[Math.floor(Math.random() * categories.length)];

            features.push({
                type: 'Feature',
                geometry: {
                    type: 'Point',
                    coordinates: [lng, lat]
                },
                properties: {
                    id: i,
                    name: `${category}_${i}`,
                    category: category
                }
            });
        }

        return { type: 'FeatureCollection', features };
    }

    createPOIIcon(category) {
        const colors = {
            restaurant: '#e74c3c',
            shopping: '#9b59b6',
            hospital: '#3498db',
            school: '#f1c40f',
            park: '#2ecc71',
            station: '#e67e22',
            default: '#95a5a6'
        };

        const color = colors[category] || colors.default;
        const canvas = document.createElement('canvas');
        canvas.width = 24;
        canvas.height = 24;
        const ctx = canvas.getContext('2d');

        ctx.beginPath();
        ctx.arc(12, 12, 10, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();
        ctx.strokeStyle = 'white';
        ctx.lineWidth = 2;
        ctx.stroke();

        return canvas.toDataURL();
    }

    async initSensorsLayer() {
        try {
            const response = await fetch('/api/v1/data/sensors?limit=30');
            const data = await response.json();

            this.sensorsDataSource = new Cesium.GeoJsonDataSource('sensors');
            await this.sensorsDataSource.load(this.sensorsToGeoJson(data.sensors));

            this.sensorsDataSource.entities.values.forEach(entity => {
                entity.point = {
                    pixelSize: 8,
                    color: Cesium.Color.RED,
                    outlineColor: Cesium.Color.WHITE,
                    outlineWidth: 2,
                };
            });

            this.viewer.dataSources.add(this.sensorsDataSource);
            this.sensorsDataSource.show = false;
        } catch (e) {
            console.error('Failed to load sensors:', e);
        }
    }

    sensorsToGeoJson(sensors) {
        const features = [];

        for (let i = 0; i < 30; i++) {
            const lng = this.centerLng + (Math.random() - 0.5) * 0.03;
            const lat = this.centerLat + (Math.random() - 0.5) * 0.03;

            features.push({
                type: 'Feature',
                geometry: {
                    type: 'Point',
                    coordinates: [lng, lat]
                },
                properties: {
                    id: i,
                    sensor_id: `SENSOR_${String(i).padStart(4, '0')}`,
                    name: `传感器_${i}`,
                    status: 'active'
                }
            });
        }

        return { type: 'FeatureCollection', features };
    }

    bindEvents() {
        document.getElementById('dataType').addEventListener('change', (e) => {
            this.dataType = e.target.value;
            this.updateHeatmapLayer();
        });

        document.getElementById('vehicleType').addEventListener('change', (e) => {
            this.vehicleType = e.target.value;
            this.updateHeatmapLayer();
        });

        document.getElementById('roadLevel').addEventListener('change', (e) => {
            this.roadLevel = e.target.value;
            this.updateHeatmapLayer();
        });

        document.getElementById('direction').addEventListener('change', (e) => {
            this.direction = e.target.value;
            this.updateHeatmapLayer();
        });

        document.getElementById('playBtn').addEventListener('click', () => this.play());
        document.getElementById('pauseBtn').addEventListener('click', () => this.pause());
        document.getElementById('resetBtn').addEventListener('click', () => this.resetTime());

        document.getElementById('timeSlider').addEventListener('input', (e) => {
            this.seekToFrame(parseInt(e.target.value, 10));
        });

        document.getElementById('playSpeed').addEventListener('change', (e) => {
            this.playbackSpeed = parseInt(e.target.value);
            document.getElementById('speedDisplay').textContent = this.playbackSpeed + 'x';
            if (this.isPlaying) {
                this.pause();
                this.play();
            }
        });

        document.getElementById('layerBuildings').addEventListener('change', (e) => {
            if (this.buildingsDataSource) {
                this.buildingsDataSource.show = e.target.checked;
            }
        });

        document.getElementById('layerRoads').addEventListener('change', (e) => {
            if (this.roadsDataSource) {
                this.roadsDataSource.show = e.target.checked;
            }
        });

        document.getElementById('layerPOIs').addEventListener('change', (e) => {
            if (this.poisDataSource) {
                this.poisDataSource.show = e.target.checked;
            }
        });

        document.getElementById('layerHeatmap').addEventListener('change', (e) => {
            const show = e.target.checked;
            for (const layer of [this.heatmapLayer, this.heatmapPrevLayer, this.heatmapNextLayer]) {
                if (layer) layer.show = show;
            }
        });

        document.getElementById('layerSensors').addEventListener('change', (e) => {
            if (this.sensorsDataSource) {
                this.sensorsDataSource.show = e.target.checked;
            }
        });

        document.getElementById('layerPrediction').addEventListener('change', (e) => {
            if (e.target.checked) {
                this.showPredictions();
            } else {
                this.hidePredictions();
            }
        });

        document.getElementById('togglePanel').addEventListener('click', () => {
            const content = document.querySelector('.panel-content');
            const btn = document.getElementById('togglePanel');
            if (content.style.display === 'none') {
                content.style.display = 'block';
                btn.textContent = '-';
            } else {
                content.style.display = 'none';
                btn.textContent = '+';
            }
        });

        document.querySelectorAll('.btn-analysis').forEach(btn => {
            btn.addEventListener('click', (e) => {
                const mode = e.target.dataset.mode;
                this.runAnalysis(mode);
            });
        });
    }

    updateHeatmapLayer() {
        this.initHeatmapLayer();
    }

    play() {
        if (this.isPlaying) return;
        this.isPlaying = true;

        const tickMs = 1000 / this.playbackSpeed;

        this.playbackInterval = setInterval(() => {
            const nextIdx = (this.currentFrameIndex + 1) % this.FRAMES_PER_DAY;
            this.advanceFrameWithBlend(nextIdx);
        }, tickMs);
    }

    pause() {
        this.isPlaying = false;
        if (this.playbackInterval) {
            clearInterval(this.playbackInterval);
            this.playbackInterval = null;
        }
    }

    resetTime() {
        this.pause();
        this.currentFrameIndex = 0;
        this.currentExactTime = new Date(this.playbackBaseDate.getTime() + this.currentFrameIndex * this.FRAME_INTERVAL_MINUTES * 60 * 1000);
        this.updateTimeDisplay();
        this.initHeatmapLayer();
    }

    seekToFrame(frameIdx) {
        frameIdx = Math.max(0, Math.min(this.FRAMES_PER_DAY - 1, frameIdx));
        this.advanceFrameWithBlend(frameIdx);
    }

    advanceFrameWithBlend(nextIdx) {
        const prevIdx = this.currentFrameIndex;
        if (prevIdx === nextIdx) {
            this.updateTimeDisplay();
            return;
        }

        this.currentFrameIndex = nextIdx;
        this.currentExactTime = new Date(this.playbackBaseDate.getTime() + nextIdx * this.FRAME_INTERVAL_MINUTES * 60 * 1000);
        this.updateTimeDisplay();
        this.updateStats();

        const params = this.buildDimensionParams();
        const nextFrameDt = this.getFrameDateTime(nextIdx);

        const nextProvider = new Cesium.UrlTemplateImageryProvider({
            url: `/api/v1/heatmap/temporal/frame/{z}/{x}/{y}.png?frame_time=${encodeURIComponent(nextFrameDt.toISOString())}&${params}`,
            maximumLevel: 18,
            minimumLevel: 8,
            tileWidth: 256,
            tileHeight: 256,
        });

        const nextLayer = this.viewer.imageryLayers.addImageryProvider(nextProvider);
        nextLayer.alpha = 0.0;

        if (this.heatmapPrevLayer) {
            try { this.viewer.imageryLayers.remove(this.heatmapPrevLayer); } catch (e) {}
        }
        this.heatmapPrevLayer = this.heatmapLayer;
        this.heatmapLayer = nextLayer;

        this._animateAlphaBlend(this.heatmapPrevLayer, nextLayer, 0.0, 0.65, 500);
    }

    _animateAlphaBlend(fadeOutLayer, fadeInLayer, startAlpha, endAlpha, durationMs) {
        const startTime = performance.now();

        const step = () => {
            const elapsed = performance.now() - startTime;
            const t = Math.min(1.0, elapsed / durationMs);
            const eased = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;

            const currentInAlpha = startAlpha + (endAlpha - startAlpha) * eased;
            const currentOutAlpha = endAlpha - (endAlpha - startAlpha) * eased;

            if (fadeInLayer) fadeInLayer.alpha = currentInAlpha;
            if (fadeOutLayer) fadeOutLayer.alpha = Math.max(0, currentOutAlpha);

            if (t < 1.0) {
                requestAnimationFrame(step);
            } else {
                if (fadeOutLayer) {
                    try { this.viewer.imageryLayers.remove(fadeOutLayer); } catch (e) {}
                }
                this.heatmapPrevLayer = null;
                if (fadeInLayer) fadeInLayer.alpha = endAlpha;
            }
        };
        requestAnimationFrame(step);
    }

    updateTimeDisplay() {
        const timeStr = this.currentExactTime.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' });
        document.getElementById('currentTime').textContent = timeStr;
        document.getElementById('frameIdx').textContent = this.currentFrameIndex;
        document.getElementById('timeSlider').value = this.currentFrameIndex;
    }

    async loadInitialData() {
        this.updateStats();
        this.updateTimeDisplay();
        this.loadDimensions();
    }

    async updateStats() {
        try {
            const vehicleCount = Math.floor(500 + Math.random() * 800);
            const pedestrianCount = Math.floor(200 + Math.random() * 500);
            const congestion = (0.3 + Math.random() * 0.5).toFixed(2);
            const avgSpeed = Math.floor(30 + Math.random() * 40);

            document.getElementById('statVehicles').textContent = vehicleCount.toLocaleString();
            document.getElementById('statPedestrians').textContent = pedestrianCount.toLocaleString();
            document.getElementById('statCongestion').textContent = congestion;
            document.getElementById('statSpeed').textContent = avgSpeed;
        } catch (e) {
            console.error('Failed to update stats:', e);
        }
    }

    async runAnalysis(mode) {
        switch (mode) {
            case 'od':
                this.showODAnalysis();
                break;
            case 'congestion':
                this.showCongestionTrace();
                break;
            case 'signal':
                this.showSignalSimulation();
                break;
        }
    }

    async loadDimensions() {
        try {
            const resp = await fetch('/api/v1/heatmap/dimensions/meta');
            if (resp.ok) {
                this.dimensions = await resp.json();
            }
        } catch (e) {
            console.warn('Dimension meta load failed', e);
        }
    }

    showCongestionTrace() {
        alert('请点击地图上的位置进行拥堵传播分析。\n将展示上下游BFS溯源和动态粒子流线。');

        const handler = new Cesium.ScreenSpaceEventHandler(this.viewer.scene.canvas);
        handler.setInputAction((click) => {
            const cartesian = this.viewer.camera.pickEllipsoid(click.position, this.viewer.scene.globe.ellipsoid);
            if (!cartesian) return;
            const cartographic = Cesium.Cartographic.fromCartesian(cartesian);
            const lon = Cesium.Math.toDegrees(cartographic.longitude);
            const lat = Cesium.Math.toDegrees(cartographic.latitude);
            handler.destroy();
            this.runCongestionPropagation(lon, lat);
        }, Cesium.ScreenSpaceEventType.LEFT_CLICK);
    }

    async runCongestionPropagation(lon, lat) {
        try {
            const url = `/api/v1/analysis/congestion-propagation?lon=${lon}&lat=${lat}`;
            const resp = await fetch(url);
            let data;
            if (resp.ok) {
                data = await resp.json();
            } else {
                data = this._buildDemoPropagationData(lon, lat);
            }
            this._renderPropagationResult(data);
        } catch (e) {
            console.error('Congestion propagation error:', e);
            this._renderPropagationResult(this._buildDemoPropagationData(lon, lat));
        }
    }

    _buildDemoPropagationData(lon, lat) {
        const makePath = (dx, dy, steps) => {
            const coords = [[lon, lat]];
            for (let i = 1; i <= steps; i++) {
                coords.push([
                    lon + dx * i * 0.003,
                    lat + dy * i * 0.003,
                ]);
            }
            return coords;
        };
        return {
            center: { lon, lat },
            upstream: {
                type: 'FeatureCollection',
                features: [
                    { type: 'Feature', geometry: { type: 'LineString', coordinates: makePath(-1, 0.5, 5) }, properties: { depth: 1, propagation_intensity: 0.9, arrival_minutes: 1 } },
                    { type: 'Feature', geometry: { type: 'LineString', coordinates: makePath(-0.5, -1, 4) }, properties: { depth: 2, propagation_intensity: 0.6, arrival_minutes: 2 } },
                ],
            },
            downstream: {
                type: 'FeatureCollection',
                features: [
                    { type: 'Feature', geometry: { type: 'LineString', coordinates: makePath(1, 0.3, 6) }, properties: { depth: 1, propagation_intensity: 0.95, arrival_minutes: 1 } },
                    { type: 'Feature', geometry: { type: 'LineString', coordinates: makePath(0.5, -0.8, 5) }, properties: { depth: 2, propagation_intensity: 0.7, arrival_minutes: 2 } },
                    { type: 'Feature', geometry: { type: 'LineString', coordinates: makePath(0.8, 0.8, 3) }, properties: { depth: 3, propagation_intensity: 0.4, arrival_minutes: 4 } },
                ],
            },
            streamlines: [
                {
                    id: 'up-1',
                    direction: 'upstream',
                    coordinates: makePath(-1, 0.5, 5),
                    speed: 0.4,
                    color: [0.2, 0.6, 1.0, 1.0],
                    particle_count: 20,
                    propagation_intensity: 0.9,
                },
                {
                    id: 'down-1',
                    direction: 'downstream',
                    coordinates: makePath(1, 0.3, 6),
                    speed: 0.5,
                    color: [1.0, 0.3, 0.2, 1.0],
                    particle_count: 25,
                    propagation_intensity: 0.95,
                },
                {
                    id: 'down-2',
                    direction: 'downstream',
                    coordinates: makePath(0.5, -0.8, 5),
                    speed: 0.35,
                    color: [1.0, 0.6, 0.2, 1.0],
                    particle_count: 18,
                    propagation_intensity: 0.7,
                },
            ],
        };
    }

    _renderPropagationResult(data) {
        this.clearPropagation();

        this.propagationDataSource = new Cesium.GeoJsonDataSource('propagation');
        const combinedFeatures = [];

        const colorForIntensity = (intensity, isUpstream) => {
            if (isUpstream) {
                return Cesium.Color.fromHsl(0.58, 1.0, 0.4 + 0.3 * intensity, 0.7);
            }
            return Cesium.Color.fromHsl(0.02, 1.0, 0.35 + 0.35 * intensity, 0.75);
        };

        if (data.upstream && data.upstream.features) {
            for (const f of data.upstream.features) {
                f.properties = f.properties || {};
                f.properties._style = 'upstream';
                combinedFeatures.push(f);
            }
        }
        if (data.downstream && data.downstream.features) {
            for (const f of data.downstream.features) {
                f.properties = f.properties || {};
                f.properties._style = 'downstream';
                combinedFeatures.push(f);
            }
        }

        const combined = {
            type: 'FeatureCollection',
            features: combinedFeatures,
        };

        this.propagationDataSource.load(combined).then(() => {
            this.propagationDataSource.entities.values.forEach(entity => {
                const isUp = entity.properties._style && entity.properties._style.getValue() === 'upstream';
                const intensity = entity.properties.propagation_intensity ? entity.properties.propagation_intensity.getValue() : 0.6;
                entity.polyline.width = 3 + 4 * intensity;
                entity.polyline.material = colorForIntensity(intensity, isUp);
                entity.polyline.arcType = Cesium.ArcType.NONE;
            });
            this.viewer.dataSources.add(this.propagationDataSource);

            this.viewer.entities.add({
                position: Cesium.Cartesian3.fromDegrees(data.center.lon, data.center.lat),
                point: {
                    pixelSize: 14,
                    color: Cesium.Color.RED,
                    outlineColor: Cesium.Color.WHITE,
                    outlineWidth: 2,
                },
                label: {
                    text: '拥堵点',
                    font: '14px sans-serif',
                    fillColor: Cesium.Color.WHITE,
                    outlineColor: Cesium.Color.BLACK,
                    outlineWidth: 3,
                    style: Cesium.LabelStyle.FILL_AND_OUTLINE,
                    pixelOffset: new Cesium.Cartesian2(0, -22),
                },
            });
        });

        this._startStreamlineParticles(data.streamlines || []);
    }

    _startStreamlineParticles(streamlines) {
        this.propagationStreamlines = [];

        for (const line of streamlines) {
            const coords = line.coordinates;
            if (!coords || coords.length < 2) continue;

            const positions = coords.map(([lon, lat]) => Cesium.Cartesian3.fromDegrees(lon, lat));
            const length = positions.length;
            const particles = [];
            const count = line.particle_count || 15;

            for (let i = 0; i < count; i++) {
                particles.push({
                    t: i / count,
                    speed: (line.speed || 0.4) * (0.8 + Math.random() * 0.4),
                });
            }

            const color = line.color
                ? new Cesium.Color(line.color[0], line.color[1], line.color[2], line.color[3] || 1.0)
                : Cesium.Color.fromCssColorString(line.direction === 'upstream' ? '#3a8dff' : '#ff4d3d');

            this.propagationStreamlines.push({
                id: line.id,
                positions,
                length,
                particles,
                color,
                direction: line.direction,
                entities: [],
            });
        }

        const particlesDataSource = new Cesium.CustomDataSource('streamline_particles');
        this.propagationParticleSystem = particlesDataSource;
        this.viewer.dataSources.add(particlesDataSource);

        for (const line of this.propagationStreamlines) {
            for (const particle of line.particles) {
                const e = particlesDataSource.entities.add({
                    position: new Cesium.CallbackProperty((() => {
                        const self = particle;
                        const positions = line.positions;
                        return (time, result) => {
                            const t = (self.t % 1.0 + 1.0) % 1.0;
                            const scaled = t * (positions.length - 1);
                            const i = Math.floor(scaled);
                            const frac = scaled - i;
                            const j = Math.min(positions.length - 1, i + 1);
                            return Cesium.Cartesian3.lerp(positions[i], positions[j], frac, new Cesium.Cartesian3());
                        };
                    })(), false),
                    point: {
                        pixelSize: 5,
                        color: line.color,
                        outlineColor: Cesium.Color.WHITE,
                        outlineWidth: 1,
                    },
                    path: {
                        show: true,
                        leadTime: 0,
                        trailTime: 6,
                        width: 2,
                        resolution: 1,
                        material: line.color.withAlpha(0.8),
                    },
                });
                line.entities.push(e);
            }
        }

        this._startParticleUpdater();
    }

    _startParticleUpdater() {
        if (this._particleUpdaterActive) return;
        this._particleUpdaterActive = true;

        let lastTime = performance.now();

        const update = () => {
            if (!this._particleUpdaterActive) return;
            const now = performance.now();
            const dt = (now - lastTime) / 1000.0;
            lastTime = now;

            for (const line of this.propagationStreamlines) {
                for (let i = 0; i < line.particles.length; i++) {
                    const p = line.particles[i];
                    p.t += p.speed * dt * 0.1;
                    if (p.t > 1.0) p.t -= 1.0;
                    if (p.t < 0.0) p.t += 1.0;
                }
            }

            requestAnimationFrame(update);
        };
        requestAnimationFrame(update);
    }

    clearPropagation() {
        this._particleUpdaterActive = false;
        this.propagationStreamlines = [];

        if (this.propagationDataSource) {
            try { this.viewer.dataSources.remove(this.propagationDataSource); } catch (e) {}
            this.propagationDataSource = null;
        }
        if (this.propagationParticleSystem) {
            try { this.viewer.dataSources.remove(this.propagationParticleSystem); } catch (e) {}
            this.propagationParticleSystem = null;
        }
    }

    async showODAnalysis() {
        try {
            const response = await fetch('/api/v1/analysis/od-flows/geojson?time_period=morning_peak&min_trips=5');
            const data = await response.json();

            if (this.odDataSource) {
                this.viewer.dataSources.remove(this.odDataSource);
            }

            this.odDataSource = new Cesium.GeoJsonDataSource('od_flows');
            await this.odDataSource.load(data);

            this.odDataSource.entities.values.forEach(entity => {
                if (entity.polyline) {
                    const tripCount = entity.properties.trip_count ? entity.properties.trip_count.getValue() : 100;
                    const width = Math.max(1, Math.min(10, tripCount / 100));

                    entity.polyline.width = width;
                    entity.polyline.material = Cesium.Color.fromCssColorString('rgba(255, 100, 100, 0.6)');
                    entity.polyline.arcType = Cesium.ArcType.NONE;
                }
            });

            this.viewer.dataSources.add(this.odDataSource);
            alert('OD分析已加载，请在地图上查看流动线');

        } catch (e) {
            console.error('OD analysis error:', e);
            alert('OD分析数据加载失败，使用演示数据');
            this.showDemoOD();
        }
    }

    showDemoOD() {
        const features = [];

        for (let i = 0; i < 20; i++) {
            const originLng = this.centerLng + (Math.random() - 0.5) * 0.04;
            const originLat = this.centerLat + (Math.random() - 0.5) * 0.04;
            const destLng = this.centerLng + (Math.random() - 0.5) * 0.04;
            const destLat = this.centerLat + (Math.random() - 0.5) * 0.04;
            const tripCount = Math.floor(Math.random() * 500) + 50;

            const midLng = (originLng + destLng) / 2;
            const midLat = (originLat + destLat) / 2 + 0.005;

            features.push({
                type: 'Feature',
                geometry: {
                    type: 'LineString',
                    coordinates: [
                        [originLng, originLat],
                        [midLng, midLat],
                        [destLng, destLat]
                    ]
                },
                properties: { trip_count: tripCount }
            });
        }

        if (this.odDataSource) {
            this.viewer.dataSources.remove(this.odDataSource);
        }

        this.odDataSource = new Cesium.GeoJsonDataSource('od_flows');
        this.odDataSource.load({ type: 'FeatureCollection', features }).then(() => {
            this.odDataSource.entities.values.forEach(entity => {
                const tripCount = entity.properties.trip_count.getValue();
                const width = Math.max(1, Math.min(8, tripCount / 80));
                entity.polyline.width = width;
                entity.polyline.material = Cesium.Color.fromCssColorString('rgba(255, 100, 100, 0.5)');
                entity.polyline.arcType = Cesium.ArcType.NONE;
            });
            this.viewer.dataSources.add(this.odDataSource);
        });
    }

    showSignalSimulation() {
        alert('信号灯配时模拟功能：\n选择交叉口和配时方案进行对比模拟\n当前为演示模式');
    }

    async showPredictions() {
        try {
            const response = await fetch('/api/v1/prediction/batch-predict', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(['SENSOR_0001', 'SENSOR_0002', 'SENSOR_0003'])
            });

            if (!response.ok) throw new Error('Prediction API not available');

            alert('预测预警已加载');
        } catch (e) {
            console.error('Prediction error:', e);
            alert('预测功能演示：显示未来15/30/60分钟的流量预测及预警');
        }
    }

    hidePredictions() {
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.app = new TrafficVizApp();
});
