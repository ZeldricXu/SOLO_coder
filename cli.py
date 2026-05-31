import asyncio
import json
import os
import sys
from typing import Optional
import click
from datetime import datetime


class CLIContext:
    def __init__(self):
        self.base_url = "http://localhost:8000"
        self.token = None
        self.config_path = os.path.expanduser("~/.edge_iot_cli.json")
        self._load_config()
    
    def _load_config(self):
        if os.path.exists(self.config_path):
            try:
                with open(self.config_path, 'r') as f:
                    data = json.load(f)
                    self.base_url = data.get("base_url", self.base_url)
                    self.token = data.get("token")
            except Exception:
                pass
    
    def save_config(self):
        data = {
            "base_url": self.base_url,
            "token": self.token
        }
        with open(self.config_path, 'w') as f:
            json.dump(data, f, indent=2)


pass_cli_ctx = click.make_pass_decorator(CLIContext, ensure=True)


def get_auth_headers(ctx: CLIContext):
    headers = {"Content-Type": "application/json"}
    if ctx.token:
        headers["Authorization"] = f"Bearer {ctx.token}"
    return headers


async def async_request(ctx: CLIContext, method: str, endpoint: str, data: dict = None, files: dict = None):
    import httpx
    
    url = f"{ctx.base_url}{endpoint}"
    headers = get_auth_headers(ctx)
    
    async with httpx.AsyncClient(timeout=30.0) as client:
        if method == "GET":
            response = await client.get(url, headers=headers)
        elif method == "POST":
            if files:
                response = await client.post(url, headers=headers, files=files, data=data)
            else:
                response = await client.post(url, headers=headers, json=data)
        elif method == "PUT":
            response = await client.put(url, headers=headers, json=data)
        elif method == "PATCH":
            response = await client.patch(url, headers=headers, json=data)
        elif method == "DELETE":
            response = await client.delete(url, headers=headers)
        else:
            raise ValueError(f"Unknown method: {method}")
        
        return response


def run_async(coro):
    return asyncio.run(coro)


@click.group()
@click.option("--base-url", envvar="EDGE_IOT_URL", help="Base URL of the API server")
@pass_cli_ctx
def cli(ctx: CLIContext, base_url: Optional[str]):
    if base_url:
        ctx.base_url = base_url


@cli.group()
def auth():
    pass


@auth.command("register")
@click.option("--username", required=True, help="Username")
@click.option("--email", required=True, help="Email address")
@click.option("--password", required=True, help="Password", hide_input=True, confirmation_prompt=True)
@pass_cli_ctx
def register(ctx: CLIContext, username: str, email: str, password: str):
    data = {"username": username, "email": email, "password": password}
    response = run_async(async_request(ctx, "POST", "/api/v1/auth/register", data))
    click.echo(json.dumps(response.json(), indent=2))


@auth.command("login")
@click.option("--username", required=True, help="Username")
@click.option("--password", required=True, help="Password", hide_input=True)
@pass_cli_ctx
def login(ctx: CLIContext, username: str, password: str):
    data = {"username": username, "password": password}
    response = run_async(async_request(ctx, "POST", "/api/v1/auth/login", data))
    result = response.json()
    
    if "data" in result and "access_token" in result["data"]:
        ctx.token = result["data"]["access_token"]
        ctx.save_config()
        click.echo("Login successful! Token saved.")
    click.echo(json.dumps(result, indent=2))


@auth.command("me")
@pass_cli_ctx
def get_me(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/auth/me"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def config():
    pass


@config.command("create")
@click.option("--config-id", required=True, help="Config ID")
@click.option("--namespace", default="default", help="Namespace")
@click.option("--params", required=True, help="Parameters as JSON string")
@click.option("--enabled/--disabled", default=True, help="Enable/disable config")
@pass_cli_ctx
def create_config(ctx: CLIContext, config_id: str, namespace: str, params: str, enabled: bool):
    data = {
        "config_id": config_id,
        "namespace": namespace,
        "parameters": json.loads(params),
        "enabled": enabled
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/configs", data))
    click.echo(json.dumps(response.json(), indent=2))


@config.command("get")
@click.option("--config-id", required=True, help="Config ID")
@click.option("--namespace", default="default", help="Namespace")
@click.option("--version", type=int, help="Specific version")
@pass_cli_ctx
def get_config(ctx: CLIContext, config_id: str, namespace: str, version: Optional[int]):
    endpoint = f"/api/v1/configs/{config_id}?namespace={namespace}"
    if version:
        endpoint += f"&version={version}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@config.command("list")
@click.option("--namespace", help="Filter by namespace")
@pass_cli_ctx
def list_configs(ctx: CLIContext, namespace: Optional[str]):
    endpoint = "/api/v1/configs"
    if namespace:
        endpoint += f"?namespace={namespace}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@config.command("history")
@click.option("--config-id", required=True, help="Config ID")
@click.option("--namespace", default="default", help="Namespace")
@pass_cli_ctx
def config_history(ctx: CLIContext, config_id: str, namespace: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/configs/{config_id}/history?namespace={namespace}"))
    click.echo(json.dumps(response.json(), indent=2))


@config.command("rollback")
@click.option("--config-id", required=True, help="Config ID")
@click.option("--namespace", default="default", help="Namespace")
@click.option("--target-version", required=True, type=int, help="Target version to rollback to")
@pass_cli_ctx
def rollback_config(ctx: CLIContext, config_id: str, namespace: str, target_version: int):
    data = {"target_version": target_version}
    response = run_async(async_request(ctx, "POST", f"/api/v1/configs/{config_id}/rollback?namespace={namespace}", data))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def devices():
    pass


@devices.command("shadow")
@click.option("--device-id", required=True, help="Device ID")
@pass_cli_ctx
def get_shadow(ctx: CLIContext, device_id: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/devices/{device_id}/shadow"))
    click.echo(json.dumps(response.json(), indent=2))


@devices.command("update-desired")
@click.option("--device-id", required=True, help="Device ID")
@click.option("--state", required=True, help="State as JSON string")
@pass_cli_ctx
def update_desired(ctx: CLIContext, device_id: str, state: str):
    data = {"device_id": device_id, "state": json.loads(state)}
    response = run_async(async_request(ctx, "PATCH", f"/api/v1/devices/{device_id}/shadow/desired", data))
    click.echo(json.dumps(response.json(), indent=2))


@devices.command("update-reported")
@click.option("--device-id", required=True, help="Device ID")
@click.option("--state", required=True, help="State as JSON string")
@pass_cli_ctx
def update_reported(ctx: CLIContext, device_id: str, state: str):
    data = {"device_id": device_id, "state": json.loads(state)}
    response = run_async(async_request(ctx, "PATCH", f"/api/v1/devices/{device_id}/shadow/reported", data))
    click.echo(json.dumps(response.json(), indent=2))


@devices.command("sync")
@click.option("--device-id", required=True, help="Device ID")
@pass_cli_ctx
def sync_shadow(ctx: CLIContext, device_id: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/devices/{device_id}/shadow/sync"))
    click.echo(json.dumps(response.json(), indent=2))


@devices.command("list")
@pass_cli_ctx
def list_devices(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/devices"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def tasks():
    pass


@tasks.command("create")
@click.option("--name", required=True, help="Task name")
@click.option("--task-type", required=True, help="Task type")
@click.option("--payload", default="{}", help="Payload as JSON string")
@click.option("--priority", type=int, default=0, help="Priority")
@pass_cli_ctx
def create_task(ctx: CLIContext, name: str, task_type: str, payload: str, priority: int):
    data = {
        "name": name,
        "task_type": task_type,
        "payload": json.loads(payload),
        "priority": priority
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/tasks", data))
    click.echo(json.dumps(response.json(), indent=2))


@tasks.command("execute")
@click.option("--task-id", required=True, help="Task ID")
@pass_cli_ctx
def execute_task(ctx: CLIContext, task_id: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/tasks/{task_id}/execute"))
    click.echo(json.dumps(response.json(), indent=2))


@tasks.command("status")
@click.option("--task-id", required=True, help="Task ID")
@pass_cli_ctx
def task_status(ctx: CLIContext, task_id: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/tasks/{task_id}"))
    click.echo(json.dumps(response.json(), indent=2))


@tasks.command("list")
@click.option("--status", help="Filter by status")
@click.option("--task-type", help="Filter by type")
@pass_cli_ctx
def list_tasks(ctx: CLIContext, status: Optional[str], task_type: Optional[str]):
    params = []
    if status:
        params.append(f"task_status={status}")
    if task_type:
        params.append(f"task_type={task_type}")
    endpoint = "/api/v1/tasks"
    if params:
        endpoint += "?" + "&".join(params)
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@tasks.command("next")
@pass_cli_ctx
def next_task(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/tasks/next"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def inference():
    pass


@inference.command("register-model")
@click.option("--model-id", required=True, help="Model ID")
@click.option("--name", required=True, help="Model name")
@click.option("--version", required=True, help="Model version")
@click.option("--model-type", required=True, help="Model type")
@click.option("--model-path", required=True, help="Model path")
@pass_cli_ctx
def register_model(ctx: CLIContext, model_id: str, name: str, version: str, model_type: str, model_path: str):
    data = {
        "model_id": model_id,
        "name": name,
        "version": version,
        "model_type": model_type,
        "model_path": model_path
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/inference/models", data))
    click.echo(json.dumps(response.json(), indent=2))


@inference.command("models")
@pass_cli_ctx
def list_models(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/inference/models"))
    click.echo(json.dumps(response.json(), indent=2))


@inference.command("quick")
@click.option("--model-id", required=True, help="Model ID")
@click.option("--device-id", required=True, help="Device ID")
@click.option("--input", required=True, help="Input data as JSON string")
@pass_cli_ctx
def quick_inference(ctx: CLIContext, model_id: str, device_id: str, input: str):
    data = {
        "model_id": model_id,
        "device_id": device_id,
        "input_data": json.loads(input)
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/inference/jobs/quick", data))
    click.echo(json.dumps(response.json(), indent=2))


@inference.command("jobs")
@click.option("--status", help="Filter by status")
@pass_cli_ctx
def list_jobs(ctx: CLIContext, status: Optional[str]):
    endpoint = "/api/v1/inference/jobs"
    if status:
        endpoint += f"?job_status={status}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def notifications():
    pass


@notifications.command("send")
@click.option("--title", required=True, help="Notification title")
@click.option("--content", required=True, help="Notification content")
@click.option("--priority", type=int, default=0, help="Priority level")
@click.option("--category", help="Category")
@pass_cli_ctx
def send_notification(ctx: CLIContext, title: str, content: str, priority: int, category: Optional[str]):
    data = {
        "title": title,
        "content": content,
        "priority": priority,
        "category": category
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/notifications", data))
    click.echo(json.dumps(response.json(), indent=2))


@notifications.command("list")
@click.option("--include-read", is_flag=True, help="Include read notifications")
@pass_cli_ctx
def list_notifications(ctx: CLIContext, include_read: bool):
    endpoint = f"/api/v1/notifications?include_read={include_read}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@notifications.command("unread-count")
@pass_cli_ctx
def unread_count(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/notifications/unread-count"))
    click.echo(json.dumps(response.json(), indent=2))


@notifications.command("mark-read")
@click.option("--notification-id", required=True, help="Notification ID")
@pass_cli_ctx
def mark_read(ctx: CLIContext, notification_id: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/notifications/{notification_id}/read"))
    click.echo(json.dumps(response.json(), indent=2))


@notifications.command("mark-all-read")
@pass_cli_ctx
def mark_all_read(ctx: CLIContext):
    response = run_async(async_request(ctx, "POST", "/api/v1/notifications/mark-all-read"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def processing():
    pass


@processing.command("execute")
@click.option("--payload", required=True, help="Payload as JSON string")
@click.option("--trace-id", help="Trace ID")
@pass_cli_ctx
def execute_processing(ctx: CLIContext, payload: str, trace_id: Optional[str]):
    data = {
        "payload": json.loads(payload),
        "trace_id": trace_id
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/processing/execute", data))
    click.echo(json.dumps(response.json(), indent=2))


@processing.command("entities")
@click.option("--entity-type", help="Filter by type")
@click.option("--status", help="Filter by status")
@pass_cli_ctx
def list_entities(ctx: CLIContext, entity_type: Optional[str], status: Optional[str]):
    params = []
    if entity_type:
        params.append(f"entity_type={entity_type}")
    if status:
        params.append(f"status={status}")
    endpoint = "/api/v1/processing/entities"
    if params:
        endpoint += "?" + "&".join(params)
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@processing.command("create-entity")
@click.option("--type", "entity_type", required=True, help="Entity type")
@click.option("--attributes", default="{}", help="Attributes as JSON string")
@pass_cli_ctx
def create_entity(ctx: CLIContext, entity_type: str, attributes: str):
    data = {
        "type": entity_type,
        "attributes": json.loads(attributes)
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/processing/entities", data))
    click.echo(json.dumps(response.json(), indent=2))


@processing.command("runs")
@click.option("--run-id", required=True, help="Run ID")
@pass_cli_ctx
def get_run(ctx: CLIContext, run_id: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/processing/runs/{run_id}"))
    click.echo(json.dumps(response.json(), indent=2))


@processing.command("schema-version")
@pass_cli_ctx
def schema_version(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/processing/schema/version"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def storage():
    pass


@storage.command("backup")
@click.option("--type", "backup_type", default="full", help="Backup type")
@pass_cli_ctx
def create_backup(ctx: CLIContext, backup_type: str):
    data = {"backup_type": backup_type}
    response = run_async(async_request(ctx, "POST", "/api/v1/storage/backup", data))
    click.echo(json.dumps(response.json(), indent=2))


@storage.command("restore")
@click.option("--backup-id", required=True, help="Backup ID")
@pass_cli_ctx
def restore_backup(ctx: CLIContext, backup_id: str):
    data = {"backup_id": backup_id}
    response = run_async(async_request(ctx, "POST", "/api/v1/storage/restore", data))
    click.echo(json.dumps(response.json(), indent=2))


@storage.command("backups")
@pass_cli_ctx
def list_backups(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/storage/backups"))
    click.echo(json.dumps(response.json(), indent=2))


@storage.command("verify")
@click.option("--backup-id", required=True, help="Backup ID")
@pass_cli_ctx
def verify_backup(ctx: CLIContext, backup_id: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/storage/backups/{backup_id}/verify"))
    click.echo(json.dumps(response.json(), indent=2))


@storage.command("stats")
@pass_cli_ctx
def storage_stats(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/storage/stats"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def ota():
    pass


@ota.command("firmware")
@click.option("--version", required=True, help="Firmware version")
@click.option("--device-model", required=True, help="Device model")
@click.option("--file-path", required=True, help="File path")
@click.option("--release-notes", help="Release notes")
@pass_cli_ctx
def create_firmware(ctx: CLIContext, version: str, device_model: str, file_path: str, release_notes: Optional[str]):
    data = {
        "version": version,
        "device_model": device_model,
        "file_path": file_path,
        "release_notes": release_notes
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/ota/firmware", data))
    click.echo(json.dumps(response.json(), indent=2))


@ota.command("firmwares")
@click.option("--device-model", help="Filter by device model")
@pass_cli_ctx
def list_firmwares(ctx: CLIContext, device_model: Optional[str]):
    endpoint = "/api/v1/ota/firmware"
    if device_model:
        endpoint += f"?device_model={device_model}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@ota.command("campaign")
@click.option("--firmware-id", required=True, help="Firmware ID")
@click.option("--name", required=True, help="Campaign name")
@click.option("--devices", required=True, help="Device IDs as JSON array string")
@click.option("--grayscale", type=int, default=100, help="Grayscale percentage")
@pass_cli_ctx
def create_campaign(ctx: CLIContext, firmware_id: str, name: str, devices: str, grayscale: int):
    data = {
        "firmware_id": firmware_id,
        "name": name,
        "device_ids": json.loads(devices),
        "grayscale_percent": grayscale
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/ota/campaigns", data))
    click.echo(json.dumps(response.json(), indent=2))


@ota.command("start-campaign")
@click.option("--campaign-id", required=True, help="Campaign ID")
@pass_cli_ctx
def start_campaign(ctx: CLIContext, campaign_id: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/ota/campaigns/{campaign_id}/start"))
    click.echo(json.dumps(response.json(), indent=2))


@ota.command("campaign-status")
@click.option("--campaign-id", required=True, help="Campaign ID")
@pass_cli_ctx
def campaign_status(ctx: CLIContext, campaign_id: str):
    response = run_async(async_request(ctx, "GET", f"/api/v1/ota/campaigns/{campaign_id}/status"))
    click.echo(json.dumps(response.json(), indent=2))


@ota.command("campaigns")
@click.option("--status", help="Filter by status")
@pass_cli_ctx
def list_campaigns(ctx: CLIContext, status: Optional[str]):
    endpoint = "/api/v1/ota/campaigns"
    if status:
        endpoint += f"?status={status}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def gateway():
    pass


@gateway.command("status")
@pass_cli_ctx
def gateway_status(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/gateway/status"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("autoscaler-metrics")
@pass_cli_ctx
def autoscaler_metrics(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/gateway/autoscaler/metrics"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("scale")
@click.option("--target", type=int, required=True, help="Target instance count (1-20)")
@pass_cli_ctx
def gateway_scale(ctx: CLIContext, target: int):
    response = run_async(async_request(ctx, "POST", f"/api/v1/gateway/autoscaler/scale?target_count={target}"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("start-autoscaler")
@pass_cli_ctx
def start_autoscaler(ctx: CLIContext):
    response = run_async(async_request(ctx, "POST", "/api/v1/gateway/autoscaler/start"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("stop-autoscaler")
@pass_cli_ctx
def stop_autoscaler(ctx: CLIContext):
    response = run_async(async_request(ctx, "POST", "/api/v1/gateway/autoscaler/stop"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("instances")
@pass_cli_ctx
def list_instances(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/gateway/instances"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("register-instance")
@click.option("--instance-id", required=True, help="Instance ID")
@click.option("--weight", type=float, default=1.0, help="Load balance weight (0.1-5.0)")
@pass_cli_ctx
def register_instance(ctx: CLIContext, instance_id: str, weight: float):
    response = run_async(async_request(ctx, "POST", f"/api/v1/gateway/instances?instance_id={instance_id}&weight={weight}"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("remove-instance")
@click.option("--instance-id", required=True, help="Instance ID")
@pass_cli_ctx
def remove_instance(ctx: CLIContext, instance_id: str):
    response = run_async(async_request(ctx, "DELETE", f"/api/v1/gateway/instances/{instance_id}"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("circuit-breakers")
@pass_cli_ctx
def circuit_breakers(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/gateway/circuit-breakers"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("reset-circuit")
@click.option("--circuit", required=True, help="Circuit breaker name")
@pass_cli_ctx
def reset_circuit(ctx: CLIContext, circuit: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/gateway/circuit-breakers/{circuit}/reset"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("reset-all-circuits")
@pass_cli_ctx
def reset_all_circuits(ctx: CLIContext):
    response = run_async(async_request(ctx, "POST", "/api/v1/gateway/circuit-breakers/reset-all"))
    click.echo(json.dumps(response.json(), indent=2))


@gateway.command("rate-limiter")
@pass_cli_ctx
def rate_limiter_metrics(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/gateway/rate-limiter/metrics"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def cache():
    pass


@cache.command("metrics")
@pass_cli_ctx
def cache_metrics(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/configs/cache/metrics"))
    click.echo(json.dumps(response.json(), indent=2))


@cache.command("invalidate")
@click.option("--config-id", help="Invalidate specific config")
@click.option("--namespace", help="Invalidate namespace")
@click.option("--version", type=int, help="Invalidate specific version")
@pass_cli_ctx
def cache_invalidate(ctx: CLIContext, config_id: Optional[str], namespace: Optional[str], version: Optional[int]):
    data = {}
    if config_id:
        data["config_id"] = config_id
    if namespace:
        data["namespace"] = namespace
    if version is not None:
        data["version"] = version
    
    response = run_async(async_request(ctx, "POST", "/api/v1/configs/cache/invalidate", data))
    click.echo(json.dumps(response.json(), indent=2))


@cache.command("reset-metrics")
@pass_cli_ctx
def reset_cache_metrics(ctx: CLIContext):
    response = run_async(async_request(ctx, "POST", "/api/v1/configs/cache/reset-metrics"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.group()
def asyncops():
    pass


@asyncops.command("submit")
@click.option("--device-id", required=True, help="Device ID")
@click.option("--operation", required=True, type=click.Choice(['update_desired', 'update_reported', 'sync', 'delete']))
@click.option("--state", default='{}', help="State as JSON string")
@click.option("--priority", type=int, default=0, help="Priority level")
@pass_cli_ctx
def submit_async(ctx: CLIContext, device_id: str, operation: str, state: str, priority: int):
    data = {
        "operations": [{
            "device_id": device_id,
            "operation": operation,
            "state": json.loads(state),
            "priority": priority,
            "async_mode": True
        }],
        "priority": priority
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/devices/async/batch", data))
    click.echo(json.dumps(response.json(), indent=2))


@asyncops.command("batch")
@click.option("--operations", required=True, help="Operations as JSON array")
@click.option("--priority", type=int, default=0, help="Priority level")
@pass_cli_ctx
def batch_async(ctx: CLIContext, operations: str, priority: int):
    data = {
        "operations": json.loads(operations),
        "priority": priority
    }
    response = run_async(async_request(ctx, "POST", "/api/v1/devices/async/batch", data))
    click.echo(json.dumps(response.json(), indent=2))


@asyncops.command("status")
@click.option("--task-id", required=True, help="Task ID")
@click.option("--wait", is_flag=True, help="Wait for completion")
@click.option("--timeout", type=float, default=30.0, help="Wait timeout")
@pass_cli_ctx
def async_status(ctx: CLIContext, task_id: str, wait: bool, timeout: float):
    endpoint = f"/api/v1/devices/async/tasks/{task_id}"
    if wait:
        endpoint += f"?wait=true&timeout={timeout}"
    response = run_async(async_request(ctx, "GET", endpoint))
    click.echo(json.dumps(response.json(), indent=2))


@asyncops.command("cancel")
@click.option("--task-id", required=True, help="Task ID")
@pass_cli_ctx
def cancel_task(ctx: CLIContext, task_id: str):
    response = run_async(async_request(ctx, "POST", f"/api/v1/devices/async/tasks/{task_id}/cancel"))
    click.echo(json.dumps(response.json(), indent=2))


@asyncops.command("list")
@click.option("--limit", type=int, default=100, help="Limit")
@pass_cli_ctx
def list_queued(ctx: CLIContext, limit: int):
    response = run_async(async_request(ctx, "GET", f"/api/v1/devices/async/tasks?limit={limit}"))
    click.echo(json.dumps(response.json(), indent=2))


@asyncops.command("metrics")
@pass_cli_ctx
def async_metrics(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/api/v1/devices/async/metrics"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.command("health")
@pass_cli_ctx
def health(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/health"))
    click.echo(json.dumps(response.json(), indent=2))


@cli.command("status")
@pass_cli_ctx
def system_status(ctx: CLIContext):
    response = run_async(async_request(ctx, "GET", "/status"))
    click.echo(json.dumps(response.json(), indent=2))


if __name__ == "__main__":
    cli()
