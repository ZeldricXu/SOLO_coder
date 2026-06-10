from datetime import datetime
from app import db
import json


class Chart(db.Model):
    __tablename__ = 'charts'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(200), nullable=False)
    description = db.Column(db.Text)
    dashboard_id = db.Column(db.Integer, db.ForeignKey('dashboards.id'), nullable=False, index=True)
    datasource_id = db.Column(db.Integer, db.ForeignKey('datasources.id'), index=True)
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    chart_type = db.Column(db.String(50), nullable=False)
    query_template = db.Column(db.Text)
    query_params = db.Column(db.JSON)
    chart_config = db.Column(db.JSON)
    position = db.Column(db.JSON)
    refresh_interval = db.Column(db.Integer)
    is_active = db.Column(db.Boolean, default=True)
    link_config = db.Column(db.JSON)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    owner = db.relationship('User', back_populates='charts')
    dashboard = db.relationship('Dashboard', back_populates='charts')
    datasource = db.relationship('DataSource', back_populates='charts')

    CHART_TYPES = {
        'line': '折线图',
        'bar': '柱状图',
        'pie': '饼图',
        'heatmap': '热力图',
        'funnel': '漏斗图',
        'scatter': '散点图',
        'gauge': '仪表盘',
    }

    DEFAULT_CONFIGS = {
        'line': {
            'title': {'text': ''},
            'tooltip': {'trigger': 'axis'},
            'legend': {'data': []},
            'xAxis': {'type': 'category', 'data': []},
            'yAxis': {'type': 'value'},
            'series': [{'type': 'line', 'data': []}],
            'grid': {'left': '3%', 'right': '4%', 'bottom': '3%', 'containLabel': True},
        },
        'bar': {
            'title': {'text': ''},
            'tooltip': {'trigger': 'axis'},
            'legend': {'data': []},
            'xAxis': {'type': 'category', 'data': []},
            'yAxis': {'type': 'value'},
            'series': [{'type': 'bar', 'data': []}],
        },
        'pie': {
            'title': {'text': ''},
            'tooltip': {'trigger': 'item'},
            'legend': {'orient': 'vertical', 'left': 'left'},
            'series': [{'type': 'pie', 'radius': '60%', 'data': []}],
        },
        'heatmap': {
            'title': {'text': ''},
            'tooltip': {'position': 'top'},
            'grid': {'height': '50%', 'top': '10%'},
            'xAxis': {'type': 'category', 'data': [], 'splitArea': {'show': True}},
            'yAxis': {'type': 'category', 'data': [], 'splitArea': {'show': True}},
            'visualMap': {'min': 0, 'max': 100, 'calculable': True, 'orient': 'horizontal', 'left': 'center', 'bottom': '5%'},
            'series': [{'type': 'heatmap', 'data': [], 'label': {'show': True}, 'emphasis': {'itemStyle': {'shadowBlur': 10, 'shadowColor': 'rgba(0, 0, 0, 0.5)'}}}],
        },
        'funnel': {
            'title': {'text': ''},
            'tooltip': {'trigger': 'item', 'formatter': '{a} <br/>{b} : {c}%'},
            'legend': {'data': []},
            'series': [{'type': 'funnel', 'left': '10%', 'width': '80%', 'label': {'show': True, 'position': 'inside'}, 'itemStyle': {'borderColor': '#fff', 'borderWidth': 2}, 'emphasis': {'label': {'fontSize': 20}}, 'data': []}],
        },
        'scatter': {
            'title': {'text': ''},
            'tooltip': {'trigger': 'item'},
            'xAxis': {'type': 'value', 'name': ''},
            'yAxis': {'type': 'value', 'name': ''},
            'series': [{'type': 'scatter', 'data': [], 'symbolSize': 10}],
        },
        'gauge': {
            'title': {'text': ''},
            'series': [{'type': 'gauge', 'progress': {'show': True}, 'detail': {'valueAnimation': True, 'formatter': '{value}%', 'color': 'auto'}, 'data': [{'value': 0, 'name': ''}]}],
        },
    }

    def get_chart_config(self):
        if isinstance(self.chart_config, str):
            try:
                return json.loads(self.chart_config)
            except json.JSONDecodeError:
                return self.DEFAULT_CONFIGS.get(self.chart_type, {})
        return self.chart_config or self.DEFAULT_CONFIGS.get(self.chart_type, {})

    def set_chart_config(self, config):
        self.chart_config = config

    def get_query_params(self):
        if isinstance(self.query_params, str):
            try:
                return json.loads(self.query_params)
            except json.JSONDecodeError:
                return {}
        return self.query_params or {}

    def set_query_params(self, params):
        self.query_params = params

    def get_position(self):
        if isinstance(self.position, str):
            try:
                return json.loads(self.position)
            except json.JSONDecodeError:
                return {'x': 0, 'y': 0, 'w': 6, 'h': 4}
        return self.position or {'x': 0, 'y': 0, 'w': 6, 'h': 4}

    def set_position(self, position):
        self.position = position

    def get_link_config(self):
        if isinstance(self.link_config, str):
            try:
                return json.loads(self.link_config)
            except json.JSONDecodeError:
                return {}
        return self.link_config or {}

    def set_link_config(self, config):
        self.link_config = config

    def get_echarts_option(self, data=None):
        config = self.get_chart_config()
        if data and 'series' in config:
            series_data = self._transform_data(data)
            for i, series in enumerate(config['series']):
                if i < len(series_data):
                    series['data'] = series_data[i]
                elif series_data:
                    series['data'] = series_data[0]
        if data and 'xAxis' in config and 'categories' in data:
            config['xAxis']['data'] = data['categories']
        if data and 'yAxis' in config and 'yCategories' in data:
            config['yAxis']['data'] = data['yCategories']
        return config

    def _transform_data(self, data):
        series_list = []
        if self.chart_type in ['line', 'bar']:
            if 'series' in data:
                for s in data['series']:
                    series_list.append(s.get('data', []))
            elif 'values' in data:
                series_list.append(data['values'])
        elif self.chart_type == 'pie':
            if 'values' in data and 'categories' in data:
                pie_data = []
                for i, name in enumerate(data['categories']):
                    value = data['values'][i] if i < len(data['values']) else 0
                    pie_data.append({'name': name, 'value': value})
                series_list.append(pie_data)
        elif self.chart_type == 'heatmap':
            if 'values' in data:
                series_list.append(data['values'])
        elif self.chart_type == 'funnel':
            if 'values' in data and 'categories' in data:
                funnel_data = []
                for i, name in enumerate(data['categories']):
                    value = data['values'][i] if i < len(data['values']) else 0
                    funnel_data.append({'name': name, 'value': value})
                series_list.append(funnel_data)
        elif self.chart_type == 'scatter':
            if 'values' in data:
                series_list.append(data['values'])
        elif self.chart_type == 'gauge':
            if 'values' in data and data['values']:
                series_list.append([{'value': data['values'][0], 'name': self.name}])
        return series_list

    def to_dict(self, include_data=False):
        data = {
            'id': self.id,
            'name': self.name,
            'description': self.description,
            'dashboard_id': self.dashboard_id,
            'datasource_id': self.datasource_id,
            'chart_type': self.chart_type,
            'chart_type_name': self.CHART_TYPES.get(self.chart_type, self.chart_type),
            'query_template': self.query_template,
            'query_params': self.get_query_params(),
            'chart_config': self.get_chart_config(),
            'position': self.get_position(),
            'link_config': self.get_link_config(),
            'refresh_interval': self.refresh_interval,
            'is_active': self.is_active,
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'updated_at': self.updated_at.isoformat() if self.updated_at else None,
        }
        return data
