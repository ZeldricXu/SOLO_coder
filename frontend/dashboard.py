import streamlit as st
import asyncio
import websockets
import json
import requests
import pandas as pd
from datetime import datetime, timedelta
from typing import Dict, Any, List, Optional
import plotly.graph_objects as go
from plotly.subplots import make_subplots

st.set_page_config(
    page_title="DataFlow 实时数据流分析平台",
    page_icon="📊",
    layout="wide",
    initial_sidebar_state="expanded"
)

API_BASE_URL = st.secrets.get("API_BASE_URL", "http://localhost:8000/api/v1")
WS_URL = st.secrets.get("WS_URL", "ws://localhost:8000/ws")


class APIClient:
    def __init__(self, base_url: str):
        self.base_url = base_url

    def get(self, endpoint: str) -> Optional[Dict[str, Any]]:
        try:
            response = requests.get(f"{self.base_url}{endpoint}", timeout=10)
            if response.status_code == 200:
                return response.json()
            return None
        except Exception as e:
            st.error(f"API请求失败: {e}")
            return None

    def post(self, endpoint: str, data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        try:
            response = requests.post(
                f"{self.base_url}{endpoint}",
                json=data,
                timeout=10
            )
            if response.status_code in (200, 201):
                return response.json()
            return None
        except Exception as e:
            st.error(f"API请求失败: {e}")
            return None

    def get_metrics(self) -> List[Dict[str, Any]]:
        result = self.get("/metrics")
        if result and result.get("code") == 200:
            return result.get("data", {}).get("metrics", [])
        return []

    def get_alert_history(self, limit: int = 50) -> List[Dict[str, Any]]:
        result = self.get(f"/alerts/history?limit={limit}")
        if result and result.get("code") == 200:
            return result.get("data", {}).get("alerts", [])
        return []

    def get_health(self) -> Optional[Dict[str, Any]]:
        return self.get("/health")


api_client = APIClient(API_BASE_URL)


@st.cache_resource
def get_data_store():
    return {
        "metrics_data": {},
        "alerts": [],
        "connected": False
    }


async def websocket_listener(ws_url: str, data_store):
    while True:
        try:
            async with websockets.connect(ws_url) as websocket:
                data_store["connected"] = True

                subscribe_msg = json.dumps({"action": "list_metrics"})
                await websocket.send(subscribe_msg)

                while True:
                    try:
                        message = await websocket.recv()
                        data = json.loads(message)

                        if data.get("event") == "metric_update":
                            metric_data = data.get("data", {})
                            metric_id = metric_data.get("metric_id")

                            if metric_id not in data_store["metrics_data"]:
                                data_store["metrics_data"][metric_id] = {
                                    "values": [],
                                    "timestamps": [],
                                    "chart_type": metric_data.get("chart_type", "line"),
                                    "name": metric_id
                                }

                            store = data_store["metrics_data"][metric_id]
                            store["values"].append(metric_data.get("value", 0))
                            store["timestamps"].append(
                                metric_data.get("timestamp", datetime.now().isoformat())
                            )

                            max_points = 100
                            if len(store["values"]) > max_points:
                                store["values"] = store["values"][-max_points:]
                                store["timestamps"] = store["timestamps"][-max_points:]

                        elif data.get("event") == "metrics_list":
                            metrics_list = data.get("data", [])
                            for metric in metrics_list:
                                if metric["metric_id"] not in data_store["metrics_data"]:
                                    data_store["metrics_data"][metric["metric_id"]] = {
                                        "values": [],
                                        "timestamps": [],
                                        "chart_type": metric.get("chart_type", "line"),
                                        "name": metric.get("metric_name", metric["metric_id"])
                                    }

                    except websockets.exceptions.ConnectionClosed:
                        data_store["connected"] = False
                        break
                    except Exception as e:
                        st.error(f"WebSocket消息处理错误: {e}")
                        break

        except Exception as e:
            data_store["connected"] = False
            await asyncio.sleep(5)


def run_websocket(ws_url, data_store):
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(websocket_listener(ws_url, data_store))


with st.sidebar:
    st.title("📊 DataFlow")
    st.subheader("实时数据流分析平台")

    st.divider()

    page = st.radio(
        "导航",
        ["仪表盘", "指标管理", "告警中心", "数据源管理", "系统状态"],
        label_visibility="collapsed"
    )

    st.divider()

    data_store = get_data_store()

    if data_store["connected"]:
        st.success("✅ WebSocket 已连接")
    else:
        st.warning("⚠️ WebSocket 未连接")

    if st.button("刷新数据"):
        st.rerun()

if page == "仪表盘":
    st.title("📈 实时监控仪表盘")

    col1, col2, col3, col4 = st.columns(4)

    with col1:
        metrics = api_client.get_metrics()
        st.metric("活跃指标", len(metrics))

    with col2:
        active_connections = 0
        health = api_client.get_health()
        if health:
            ws_status = health.get("services", {}).get("websocket", {})
            active_connections = ws_status.get("active_connections", 0)
        st.metric("在线连接数", active_connections)

    with col3:
        alerts = api_client.get_alert_history(limit=10)
        critical_alerts = sum(1 for a in alerts if a.get("severity") == "critical")
        st.metric("关键告警", critical_alerts)

    with col4:
        st.metric("数据质量", "98.5%")

    st.divider()

    if not data_store["metrics_data"]:
        st.info("暂无实时数据，请先配置指标。")
    else:
        metric_ids = list(data_store["metrics_data"].keys())
        cols_per_row = 2
        rows = [metric_ids[i:i + cols_per_row] for i in range(0, len(metric_ids), cols_per_row)]

        for row in rows:
            cols = st.columns(cols_per_row)
            for idx, metric_id in enumerate(row):
                with cols[idx]:
                    metric_data = data_store["metrics_data"][metric_id]
                    st.subheader(f"📊 {metric_data.get('name', metric_id)}")

                    if metric_data["values"]:
                        current_value = metric_data["values"][-1]
                        chart_type = metric_data.get("chart_type", "line")

                        fig = go.Figure()

                        if chart_type == "line":
                            fig.add_trace(go.Scatter(
                                x=metric_data["timestamps"],
                                y=metric_data["values"],
                                mode='lines+markers',
                                name=metric_id
                            ))
                        elif chart_type == "bar":
                            fig.add_trace(go.Bar(
                                x=metric_data["timestamps"],
                                y=metric_data["values"],
                                name=metric_id
                            ))
                        elif chart_type == "area":
                            fig.add_trace(go.Scatter(
                                x=metric_data["timestamps"],
                                y=metric_data["values"],
                                mode='lines',
                                fill='tozeroy',
                                name=metric_id
                            ))

                        fig.update_layout(
                            margin=dict(l=0, r=0, t=0, b=0),
                            height=200,
                            xaxis=dict(showticklabels=False),
                            yaxis=dict(title=None)
                        )

                        st.plotly_chart(fig, use_container_width=True)

                        st.metric(
                            "当前值",
                            f"{current_value:.2f}",
                            delta=None
                        )
                    else:
                        st.info("等待数据...")

elif page == "指标管理":
    st.title("⚙️ 指标配置管理")

    tab1, tab2 = st.tabs(["创建指标", "指标列表"])

    with tab1:
        st.subheader("新建指标配置")

        col1, col2 = st.columns(2)

        with col1:
            metric_name = st.text_input("指标名称", placeholder="例如：实时订单数")
            source = st.text_input("数据源标识", placeholder="例如：kafka_orders")

            aggregation = st.selectbox(
                "聚合函数",
                options=["count", "sum", "avg"],
                format_func=lambda x: {
                    "count": "计数 (count)",
                    "sum": "求和 (sum)",
                    "avg": "平均值 (avg)"
                }[x]
            )

            field = st.text_input(
                "聚合字段",
                placeholder="count可选，sum/avg必填",
                disabled=(aggregation == "count")
            )

        with col2:
            time_window = st.selectbox(
                "时间窗口",
                options=["30s", "60s", "5m", "15m", "1h"],
                index=1
            )

            group_by_str = st.text_input(
                "分组字段 (逗号分隔)",
                placeholder="例如：region,category"
            )

            chart_type = st.selectbox(
                "图表类型",
                options=["line", "bar", "area"],
                format_func=lambda x: {
                    "line": "折线图",
                    "bar": "柱状图",
                    "area": "面积图"
                }[x]
            )

            add_alert = st.checkbox("添加告警规则")

        if add_alert:
            st.divider()
            st.subheader("告警规则")

            col_a1, col_a2 = st.columns(2)
            with col_a1:
                condition = st.text_input(
                    "触发条件",
                    placeholder="例如：value < 10 或 value > 100"
                )
            with col_a2:
                severity = st.selectbox(
                    "严重级别",
                    options=["info", "warning", "critical"],
                    format_func=lambda x: {
                        "info": "信息 (Info)",
                        "warning": "警告 (Warning)",
                        "critical": "严重 (Critical)"
                    }[x]
                )

        if st.button("创建指标", type="primary", use_container_width=True):
            if not metric_name or not source:
                st.error("请填写指标名称和数据源标识")
            elif aggregation != "count" and not field:
                st.error("sum/avg聚合需要指定聚合字段")
            else:
                group_by = []
                if group_by_str.strip():
                    group_by = [g.strip() for g in group_by_str.split(",") if g.strip()]

                alert_rules = []
                if add_alert and condition:
                    alert_rules.append({
                        "condition": condition,
                        "severity": severity,
                        "notify_channel": "slack"
                    })

                config = {
                    "metric_name": metric_name,
                    "source": source,
                    "aggregation": aggregation,
                    "field": field if aggregation != "count" else None,
                    "time_window": time_window,
                    "group_by": group_by,
                    "chart_type": chart_type,
                    "alert_rules": alert_rules,
                    "is_active": True
                }

                result = api_client.post("/metrics/config", config)

                if result and result.get("code") == 200:
                    st.success(f"指标创建成功！ID: {result['data']['metric_id']}")
                else:
                    st.error("指标创建失败")

    with tab2:
        st.subheader("已配置指标列表")

        metrics = api_client.get_metrics()

        if not metrics:
            st.info("暂无已配置的指标")
        else:
            for metric in metrics:
                with st.expander(
                    f"📊 {metric['metric_name']} ({metric['metric_id']})",
                    expanded=False
                ):
                    col1, col2, col3 = st.columns(3)

                    with col1:
                        st.write(f"**数据源:** {metric['source']}")
                        st.write(f"**聚合函数:** {metric['aggregation']}")

                    with col2:
                        st.write(f"**时间窗口:** {metric['time_window']}")
                        st.write(f"**图表类型:** {metric['chart_type']}")

                    with col3:
                        st.write(f"**告警规则:** {metric['alert_rules_count']} 条")
                        st.write(f"**状态:** {'✅ 活跃' if metric['is_active'] else '❌ 停用'}")

                    if metric['group_by']:
                        st.write(f"**分组字段:** {', '.join(metric['group_by'])}")

                    if metric['field']:
                        st.write(f"**聚合字段:** {metric['field']}")

elif page == "告警中心":
    st.title("🔔 告警中心")

    col1, col2, col3 = st.columns(3)

    alerts = api_client.get_alert_history(limit=100)

    critical_count = sum(1 for a in alerts if a.get("severity") == "critical")
    warning_count = sum(1 for a in alerts if a.get("severity") == "warning")
    info_count = sum(1 for a in alerts if a.get("severity") == "info")

    with col1:
        st.metric("🔴 严重告警", critical_count)

    with col2:
        st.metric("🟡 警告", warning_count)

    with col3:
        st.metric("🔵 信息", info_count)

    st.divider()

    st.subheader("告警历史")

    if not alerts:
        st.info("暂无告警记录")
    else:
        for alert in alerts[:20]:
            severity = alert.get("severity", "info")

            if severity == "critical":
                bg_color = "#ffebee"
                icon = "🔴"
            elif severity == "warning":
                bg_color = "#fff3e0"
                icon = "🟡"
            else:
                bg_color = "#e3f2fd"
                icon = "🔵"

            with st.container():
                st.markdown(
                    f"""
                    <div style="background-color: {bg_color}; padding: 12px; border-radius: 8px; margin-bottom: 8px;">
                        <div style="display: flex; justify-content: space-between; align-items: center;">
                            <span style="font-weight: bold;">{icon} {alert.get('metric_name', alert['metric_id'])}</span>
                            <span style="font-size: 0.85em; color: #666;">{alert.get('timestamp', '')[:19]}</span>
                        </div>
                        <div style="margin-top: 4px;">
                            <span style="font-weight: 500;">{alert.get('message', '')}</span>
                        </div>
                        <div style="margin-top: 4px; font-size: 0.85em; color: #666;">
                            当前值: {alert.get('value', 'N/A')} | 条件: {alert.get('threshold_condition', '')}
                        </div>
                    </div>
                    """,
                    unsafe_allow_html=True
                )

elif page == "数据源管理":
    st.title("🔌 数据源管理")

    tab1, tab2 = st.tabs(["添加数据源", "已配置数据源"])

    with tab1:
        st.subheader("新建数据源")

        source_type = st.selectbox(
            "数据源类型",
            options=["kafka", "mysql"],
            format_func=lambda x: {
                "kafka": "Kafka 消息队列",
                "mysql": "MySQL 数据库"
            }[x]
        )

        source_id = st.text_input("数据源ID", placeholder="例如：kafka_orders")

        if source_type == "kafka":
            col1, col2 = st.columns(2)
            with col1:
                bootstrap_servers = st.text_input(
                    "Kafka 服务器",
                    value="localhost:9092"
                )
            with col2:
                group_id = st.text_input(
                    "消费组ID",
                    value="dataflow-consumer"
                )

            topics_str = st.text_input(
                "订阅主题 (逗号分隔)",
                placeholder="例如：orders, payments"
            )

        else:
            col1, col2 = st.columns(2)
            with col1:
                mysql_host = st.text_input("主机地址", value="localhost")
                mysql_user = st.text_input("用户名", value="root")
            with col2:
                mysql_port = st.number_input("端口", value=3306, min_value=1, max_value=65535)
                mysql_password = st.text_input("密码", type="password")

            mysql_database = st.text_input("数据库名")
            tables_str = st.text_input(
                "监听表 (逗号分隔)",
                placeholder="例如：orders, users"
            )

        if st.button("添加数据源", type="primary", use_container_width=True):
            if not source_id:
                st.error("请输入数据源ID")
            else:
                config = {
                    "source_id": source_id,
                    "source_type": source_type,
                    "config": {},
                    "is_active": True
                }

                if source_type == "kafka":
                    topics = []
                    if topics_str.strip():
                        topics = [t.strip() for t in topics_str.split(",") if t.strip()]

                    config["config"] = {
                        "bootstrap_servers": bootstrap_servers,
                        "group_id": group_id,
                        "topics": topics
                    }
                else:
                    tables = []
                    if tables_str.strip():
                        tables = [t.strip() for t in tables_str.split(",") if t.strip()]

                    config["config"] = {
                        "host": mysql_host,
                        "port": mysql_port,
                        "user": mysql_user,
                        "password": mysql_password,
                        "database": mysql_database,
                        "polling_tables": tables,
                        "polling_interval": 5
                    }

                result = api_client.post("/datasources", config)

                if result and result.get("code") == 200:
                    st.success("数据源添加成功！")
                else:
                    st.error("数据源添加失败")

    with tab2:
        st.subheader("已配置的数据源")

        health = api_client.get_health()
        if health:
            datasources = health.get("services", {}).get("connector_manager", [])

            if not datasources:
                st.info("暂无已配置的数据源")
            else:
                for ds in datasources:
                    with st.expander(
                        f"{'✅' if ds.get('is_connected') else '❌'} {ds['source_id']}",
                        expanded=False
                    ):
                        col1, col2, col3 = st.columns(3)

                        with col1:
                            st.write(f"**类型:** {ds['source_type']}")

                        with col2:
                            st.write(f"**连接状态:** {'已连接' if ds.get('is_connected') else '未连接'}")

                        with col3:
                            st.write(f"**运行状态:** {'运行中' if ds.get('is_running') else '已停止'}")

                        st.write(f"**重连尝试:** {ds.get('reconnect_attempts', 0)} 次")

elif page == "系统状态":
    st.title("🖥️ 系统状态")

    health = api_client.get_health()

    if not health:
        st.error("无法获取系统状态，请检查后端服务是否正常运行")
    else:
        col1, col2 = st.columns(2)

        with col1:
            st.subheader("📊 整体状态")
            st.metric("系统状态", "正常" if health.get("status") == "healthy" else "异常")
            st.write(f"**检查时间:** {health.get('timestamp', 'N/A')}")

        with col2:
            st.subheader("⚡ 服务状态")

            services = health.get("services", {})

            for service_name, status in services.items():
                if isinstance(status, dict):
                    is_ok = status.get("is_connected", True) if "is_connected" in status else True
                    icon = "✅" if is_ok else "❌"
                    st.write(f"{icon} **{service_name}**")
                elif isinstance(status, list):
                    st.write(f"✅ **{service_name}** ({len(status)} 个)")

        st.divider()

        st.subheader("🔧 详细状态")

        services = health.get("services", {})

        tab_names = ["指标管理器", "WebSocket", "存储", "告警引擎"]
        tab_keys = ["metric_manager", "websocket", "storage", "alerts"]

        tabs = st.tabs(tab_names)

        for idx, (tab, key) in enumerate(zip(tabs, tab_keys)):
            with tab:
                status = services.get(key, {})
                if status:
                    for k, v in status.items():
                        st.write(f"**{k}:** {v}")
                else:
                    st.info("暂无详细信息")
