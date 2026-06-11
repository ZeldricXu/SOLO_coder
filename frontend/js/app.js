class TrafficVizApp {
    constructor() {
        this.viewer = null;
        this.heatmapImageryProvider = null;
        this.heatmapLayer = null;
        this.buildingsDataSource = null;
        this.roadsDataSource = null;
        this.poisDataSource = null;
        this.sensorsDataSource = null;
        this.odDataSource = null;

        this.isPlaying = false;
        this.playbackSpeed = 1;
        this.currentTime = new Date();
        this.timeRange = { start: null, end: null };
        this.playbackInterval = null;

        this.dataType = 'vehicle';
        this.vehicleType = 'all';

        this.centerLng = 116.4074;
        this.centerLat = 39.9042;

        this.init();
    }

    init() {
        this.initCesium();
        this.bindEvents();
        this.loadInitialData();
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
        this.heatmapImageryProvider = new Cesium.UrlTemplateImageryProvider({
            url: '/api/v1/heatmap/tile/{z}/{x}/{y}.png?data_type=' + this.dataType + '&vehicle_type=' + this.vehicleType,
            maximumLevel: 20,
            minimumLevel: 8,
            tileWidth: 256,
            tileHeight: 256,
        });

        this.heatmapLayer = this.viewer.imageryLayers.addImageryProvider(
            this.heatmapImageryProvider
        );
        this.heatmapLayer.alpha = 0.6;
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

        document.getElementById('playBtn').addEventListener('click', () => this.play());
        document.getElementById('pauseBtn').addEventListener('click', () => this.pause());
        document.getElementById('resetBtn').addEventListener('click', () => this.resetTime());

        document.getElementById('timeSlider').addEventListener('input', (e) => {
            this.updateTimeFromSlider(parseFloat(e.target.value));
        });

        document.getElementById('playSpeed').addEventListener('change', (e) => {
            this.playbackSpeed = parseInt(e.target.value);
            document.getElementById('speedDisplay').textContent = this.playbackSpeed + 'x';
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
            if (this.heatmapLayer) {
                this.heatmapLayer.show = e.target.checked;
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
        if (this.heatmapLayer) {
            this.viewer.imageryLayers.remove(this.heatmapLayer);
        }

        this.heatmapImageryProvider = new Cesium.UrlTemplateImageryProvider({
            url: `/api/v1/heatmap/tile/{z}/{x}/{y}.png?data_type=${this.dataType}&vehicle_type=${this.vehicleType}`,
            maximumLevel: 20,
            minimumLevel: 8,
            tileWidth: 256,
            tileHeight: 256,
        });

        this.heatmapLayer = this.viewer.imageryLayers.addImageryProvider(
            this.heatmapImageryProvider
        );
        this.heatmapLayer.alpha = 0.6;
    }

    play() {
        if (this.isPlaying) return;
        this.isPlaying = true;

        const step = 5 * 60 * 1000 * this.playbackSpeed;

        this.playbackInterval = setInterval(() => {
            this.currentTime = new Date(this.currentTime.getTime() + step);
            this.updateTimeDisplay();
            this.updateHeatmapWithTime();
        }, 1000);
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
        this.currentTime = new Date();
        this.updateTimeDisplay();
        this.updateHeatmapWithTime();
    }

    updateTimeFromSlider(value) {
        const now = new Date();
        const start = new Date(now.getTime() - 24 * 60 * 60 * 1000);
        const duration = now - start;
        this.currentTime = new Date(start.getTime() + (value / 100) * duration);
        this.updateTimeDisplay();
        this.updateHeatmapWithTime();
    }

    updateTimeDisplay() {
        const timeStr = this.currentTime.toLocaleTimeString('zh-CN', { hour12: false });
        document.getElementById('currentTime').textContent = timeStr;
    }

    updateHeatmapWithTime() {
        if (this.heatmapLayer) {
            this.heatmapLayer.show = false;
            setTimeout(() => {
                if (this.heatmapLayer) {
                    this.heatmapLayer.show = true;
                }
            }, 50);
        }
        this.updateStats();
    }

    async loadInitialData() {
        this.updateStats();
        this.updateTimeDisplay();
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

    showCongestionTrace() {
        alert('拥堵溯源功能：点击地图上的传感器节点查看上下游拥堵情况');

        const handler = new Cesium.ScreenSpaceEventHandler(this.viewer.scene.canvas);
        handler.setInputAction((click) => {
            const pickedFeature = this.viewer.scene.pick(click.position);
            if (pickedFeature && pickedFeature.id) {
                alert(`传感器：${pickedFeature.id._name || '未知'}\n上下游拥堵分析中...`);
            }
        }, Cesium.ScreenSpaceEventType.LEFT_CLICK);
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
