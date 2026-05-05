from flask import Blueprint, request, jsonify
from datetime import datetime, date
from typing import Dict, Any, List

from ..core import (
    model_manager,
    version_manager,
    deployment_manager,
    inference_service,
    monitoring_manager,
    training_manager,
    BatchingConfig,
    DeploymentHealthConfig,
    VersionDiffReport
)
from ..storage import file_store

api_bp = Blueprint('api', __name__)


def success_response(data: Any = None, message: str = "success") -> Dict:
    response = {"code": 200, "message": message}
    if data is not None:
        response["data"] = data
    return response


def error_response(code: int, message: str) -> Dict:
    return {"code": code, "message": message}


@api_bp.route('/health', methods=['GET'])
def health_check():
    return jsonify(success_response({"status": "healthy"}))


@api_bp.route('/models', methods=['POST'])
def create_model():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_name', 'model_type', 'framework']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    model = model_manager.create_model(
        model_name=data['model_name'],
        model_type=data['model_type'],
        framework=data['framework'],
        model_id=data.get('model_id'),
        tags=data.get('tags', []),
        description=data.get('description', '')
    )

    if model:
        return jsonify(success_response(model.to_dict()))
    return jsonify(error_response(500, "Failed to create model")), 500


@api_bp.route('/models/<model_id>', methods=['GET'])
def get_model(model_id: str):
    model = model_manager.get_model(model_id)
    if model:
        return jsonify(success_response(model.to_dict()))
    return jsonify(error_response(404, "Model not found")), 404


@api_bp.route('/models', methods=['GET'])
def list_models():
    model_type = request.args.get('model_type')
    framework = request.args.get('framework')
    status = request.args.get('status')

    models = model_manager.list_models(
        model_type=model_type,
        framework=framework,
        status=status
    )

    return jsonify(success_response([m.to_dict() for m in models]))


@api_bp.route('/models/<model_id>', methods=['PUT'])
def update_model(model_id: str):
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    updated = model_manager.update_model(model_id, data)
    if updated:
        return jsonify(success_response(updated.to_dict()))
    return jsonify(error_response(404, "Model not found")), 404


@api_bp.route('/models/<model_id>', methods=['DELETE'])
def delete_model(model_id: str):
    if model_manager.delete_model(model_id):
        return jsonify(success_response(message="Model deleted"))
    return jsonify(error_response(404, "Model not found")), 404


@api_bp.route('/models/<model_id>/tags', methods=['POST'])
def add_tags(model_id: str):
    data = request.get_json()
    if not data or 'tags' not in data:
        return jsonify(error_response(400, "Missing tags field")), 400

    updated = model_manager.add_tags(model_id, data['tags'])
    if updated:
        return jsonify(success_response(updated.to_dict()))
    return jsonify(error_response(404, "Model not found")), 404


@api_bp.route('/models/<model_id>/tags', methods=['DELETE'])
def remove_tags(model_id: str):
    data = request.get_json()
    if not data or 'tags' not in data:
        return jsonify(error_response(400, "Missing tags field")), 400

    updated = model_manager.remove_tags(model_id, data['tags'])
    if updated:
        return jsonify(success_response(updated.to_dict()))
    return jsonify(error_response(404, "Model not found")), 404


@api_bp.route('/models/<model_id>/batching-config', methods=['GET'])
def get_model_batching_config(model_id: str):
    config = inference_service.get_model_batching_config(model_id)
    if config:
        return jsonify(success_response(config.to_dict()))
    default_config = BatchingConfig.get_default_for_model_type("other")
    return jsonify(success_response(default_config.to_dict()))


@api_bp.route('/models/<model_id>/batching-config', methods=['PUT'])
def update_model_batching_config(model_id: str):
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    success = inference_service.update_model_batching_config(model_id, data)
    if success:
        config = inference_service.get_model_batching_config(model_id)
        return jsonify(success_response({
            "model_id": model_id,
            "batching_config": config.to_dict() if config else None,
            "message": "Batching config updated successfully"
        }))
    return jsonify(error_response(500, "Failed to update batching config")), 500


@api_bp.route('/models/versions', methods=['POST'])
def create_version():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_id', 'version', 'model_file', 'model_size']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    version = version_manager.create_version(
        model_id=data['model_id'],
        version=data['version'],
        model_file=data['model_file'],
        model_size=data['model_size'],
        training_params=data.get('training_params', {}),
        accuracy=data.get('accuracy'),
        checksum=data.get('checksum', ''),
        notes=data.get('notes', '')
    )

    if version:
        return jsonify(success_response(version.to_dict()))
    return jsonify(error_response(500, "Failed to create version")), 500


@api_bp.route('/models/versions', methods=['GET'])
def list_versions():
    model_id = request.args.get('model_id')
    if not model_id:
        return jsonify(error_response(400, "Missing model_id parameter")), 400

    versions = version_manager.get_model_versions(model_id)
    versions_data = [{
        "version": v.version,
        "version_id": v.version_id,
        "accuracy": v.accuracy,
        "model_size": v.model_size,
        "created_at": v.created_at.isoformat() + "Z"
    } for v in versions]

    return jsonify(success_response({"versions": versions_data}))


@api_bp.route('/models/versions/compare', methods=['GET'])
def compare_versions():
    model_id = request.args.get('model_id')
    version1 = request.args.get('version1')
    version2 = request.args.get('version2')

    if not all([model_id, version1, version2]):
        return jsonify(error_response(400, "Missing required parameters: model_id, version1, version2")), 400

    comparison = version_manager.compare_versions(model_id, version1, version2)
    if comparison:
        return jsonify(success_response(comparison))
    return jsonify(error_response(404, "One or both versions not found")), 404


@api_bp.route('/models/versions/diff-report', methods=['GET'])
def get_version_diff_report():
    model_id = request.args.get('model_id')
    version1 = request.args.get('version1')
    version2 = request.args.get('version2')

    if not all([model_id, version1, version2]):
        return jsonify(error_response(400, "Missing required parameters: model_id, version1, version2")), 400

    report = version_manager.get_diff_report_as_dict(model_id, version1, version2)
    if report:
        return jsonify(success_response(report))
    return jsonify(error_response(404, "One or both versions not found")), 404


@api_bp.route('/models/versions/<version_id>', methods=['GET'])
def get_version(version_id: str):
    version = version_manager.get_version(version_id)
    if version:
        return jsonify(success_response(version.to_dict()))
    return jsonify(error_response(404, "Version not found")), 404


@api_bp.route('/models/versions/<version_id>', methods=['DELETE'])
def delete_version(version_id: str):
    if version_manager.delete_version(version_id):
        return jsonify(success_response(message="Version deleted"))
    return jsonify(error_response(404, "Version not found")), 404


@api_bp.route('/models/deploy', methods=['POST'])
def deploy_model():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_id', 'version']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    health_config_data = data.get('health_config')
    health_config = None
    if health_config_data:
        health_config = DeploymentHealthConfig.from_dict(health_config_data)

    deployment = deployment_manager.create_deployment(
        model_id=data['model_id'],
        version=data['version'],
        replicas=data.get('replicas', 1),
        custom_port=data.get('port'),
        enable_health_check=data.get('enable_health_check', True),
        enable_auto_rollback=data.get('enable_auto_rollback', True),
        enable_latency_check=data.get('enable_latency_check', True),
        health_config=health_config
    )

    if deployment:
        return jsonify(success_response({
            "deploy_id": deployment.deploy_id,
            "service_url": deployment.service_url,
            "status": deployment.deploy_status
        }))
    return jsonify(error_response(500, "Failed to deploy model")), 500


@api_bp.route('/models/deployments/<deploy_id>', methods=['GET'])
def get_deployment(deploy_id: str):
    deployment = deployment_manager.get_deployment(deploy_id)
    if deployment:
        health = deployment_manager.get_deployment_health(deploy_id)
        result = deployment.to_dict()
        result['health'] = health
        return jsonify(success_response(result))
    return jsonify(error_response(404, "Deployment not found")), 404


@api_bp.route('/models/deployments/<deploy_id>/stop', methods=['POST'])
def stop_deployment(deploy_id: str):
    if deployment_manager.stop_deployment(deploy_id):
        return jsonify(success_response(message="Deployment stopped"))
    return jsonify(error_response(404, "Deployment not found")), 404


@api_bp.route('/models/deployments/<deploy_id>/restart', methods=['POST'])
def restart_deployment(deploy_id: str):
    deployment = deployment_manager.restart_deployment(deploy_id)
    if deployment:
        return jsonify(success_response(deployment.to_dict()))
    return jsonify(error_response(404, "Deployment not found")), 404


@api_bp.route('/models/deployments', methods=['GET'])
def list_deployments():
    model_id = request.args.get('model_id')
    status = request.args.get('status')

    if model_id:
        deployments = deployment_manager.get_model_deployments(model_id)
    else:
        deployments = deployment_manager.list_all_deployments()

    if status:
        deployments = [d for d in deployments if d.deploy_status == status]

    return jsonify(success_response([d.to_dict() for d in deployments]))


@api_bp.route('/models/inference', methods=['POST'])
def execute_inference():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_id', 'input_data']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    version = data.get('version')
    use_current = version is None

    result = inference_service.execute_inference(
        model_id=data['model_id'],
        version=version if version else "",
        input_data=data['input_data'],
        use_current_version=use_current
    )

    if result.get('success'):
        return jsonify(success_response({
            "result": result.get('result'),
            "inference_time": result.get('inference_time_ms')
        }))
    return jsonify(error_response(500, result.get('error', 'Inference failed'))), 500


@api_bp.route('/models/inference/batch', methods=['POST'])
def execute_batch_inference():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_id', 'inputs']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    version = data.get('version')
    use_current = version is None

    results = inference_service.execute_batch_inference(
        model_id=data['model_id'],
        version=version if version else "",
        inputs=data['inputs'],
        use_current_version=use_current
    )

    return jsonify(success_response({"results": results}))


@api_bp.route('/models/loaded', methods=['GET'])
def list_loaded_models():
    loaded = inference_service.list_loaded_models()
    return jsonify(success_response(loaded))


@api_bp.route('/models/stats', methods=['GET'])
def get_model_stats():
    model_id = request.args.get('model_id')
    stat_date = request.args.get('date')

    if not model_id:
        return jsonify(error_response(400, "Missing model_id parameter")), 400

    stats = monitoring_manager.get_stats(model_id, stat_date)
    if stats:
        return jsonify(success_response(stats.to_dict()))
    return jsonify(success_response({
        "model_id": model_id,
        "stat_date": stat_date if stat_date else date.today().isoformat(),
        "request_count": 0,
        "avg_latency": 0,
        "max_latency": 0,
        "min_latency": 0,
        "throughput": 0,
        "error_count": 0
    }))


@api_bp.route('/models/stats/range', methods=['GET'])
def get_stats_range():
    model_id = request.args.get('model_id')
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')

    if not all([model_id, start_date, end_date]):
        return jsonify(error_response(400, "Missing required parameters: model_id, start_date, end_date")), 400

    aggregated = monitoring_manager.get_aggregated_stats(model_id, start_date, end_date)
    return jsonify(success_response(aggregated))


@api_bp.route('/models/inferences/recent', methods=['GET'])
def get_recent_inferences():
    model_id = request.args.get('model_id')
    limit = request.args.get('limit', 100, type=int)

    if not model_id:
        return jsonify(error_response(400, "Missing model_id parameter")), 400

    inferences = monitoring_manager.get_recent_inferences(model_id, limit)
    return jsonify(success_response([i.to_dict() for i in inferences]))


@api_bp.route('/models/inferences/<request_id>', methods=['GET'])
def get_inference(request_id: str):
    inference = monitoring_manager.get_inference(request_id)
    if inference:
        return jsonify(success_response(inference.to_dict()))
    return jsonify(error_response(404, "Inference not found")), 404


@api_bp.route('/trainings', methods=['POST'])
def start_training():
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['model_id', 'version_id']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    training = training_manager.start_training(
        model_id=data['model_id'],
        version_id=data['version_id'],
        training_params=data.get('training_params', {}),
        dataset_info=data.get('dataset_info', {})
    )

    if training:
        return jsonify(success_response(training.to_dict()))
    return jsonify(error_response(500, "Failed to start training")), 500


@api_bp.route('/trainings/<training_id>/complete', methods=['POST'])
def complete_training(training_id: str):
    data = request.get_json()
    if not data:
        return jsonify(error_response(400, "No data provided")), 400

    required_fields = ['training_metrics', 'training_time']
    for field in required_fields:
        if field not in data:
            return jsonify(error_response(400, f"Missing required field: {field}")), 400

    training = training_manager.complete_training(
        training_id=training_id,
        training_metrics=data['training_metrics'],
        training_time=data['training_time']
    )

    if training:
        return jsonify(success_response(training.to_dict()))
    return jsonify(error_response(404, "Training not found")), 404


@api_bp.route('/trainings/<training_id>/fail', methods=['POST'])
def fail_training(training_id: str):
    data = request.get_json()
    error_message = data.get('error_message', 'Unknown error') if data else 'Unknown error'

    training = training_manager.fail_training(training_id, error_message)
    if training:
        return jsonify(success_response(training.to_dict()))
    return jsonify(error_response(404, "Training not found")), 404


@api_bp.route('/trainings/<training_id>', methods=['GET'])
def get_training(training_id: str):
    training = training_manager.get_training(training_id)
    if training:
        return jsonify(success_response(training.to_dict()))
    return jsonify(error_response(404, "Training not found")), 404


@api_bp.route('/trainings', methods=['GET'])
def list_trainings():
    model_id = request.args.get('model_id')
    status = request.args.get('status')

    if model_id:
        trainings = training_manager.get_model_trainings(model_id)
    else:
        trainings = training_manager.list_all_trainings()

    if status:
        trainings = [t for t in trainings if t.status == status]

    return jsonify(success_response([t.to_dict() for t in trainings]))


@api_bp.route('/trainings/model/<model_id>/best', methods=['GET'])
def get_best_training(model_id: str):
    metric_key = request.args.get('metric', 'accuracy')
    training = training_manager.get_best_training(model_id, metric_key)

    if training:
        return jsonify(success_response(training.to_dict()))
    return jsonify(error_response(404, "No completed training found")), 404


@api_bp.route('/models/download', methods=['GET'])
def get_model_download_link():
    model_id = request.args.get('model_id')
    version = request.args.get('version')
    filename = request.args.get('filename')

    if not all([model_id, version, filename]):
        return jsonify(error_response(400, "Missing required parameters: model_id, version, filename")), 400

    file_path = file_store.create_download_link(model_id, version, filename)
    if file_path:
        return jsonify(success_response({
            "file_path": file_path,
            "model_id": model_id,
            "version": version,
            "filename": filename
        }))
    return jsonify(error_response(404, "File not found")), 404


@api_bp.route('/models/versions/<version_id>/verify', methods=['POST'])
def verify_model_file(version_id: str):
    version = version_manager.get_version(version_id)
    if not version:
        return jsonify(error_response(404, "Version not found")), 404

    is_valid = file_store.verify_file(
        version.model_id,
        version.version,
        version.model_file,
        version.checksum
    )

    return jsonify(success_response({
        "valid": is_valid,
        "version_id": version_id,
        "model_id": version.model_id
    }))


@api_bp.route('/models/inference/batching/stats', methods=['GET'])
def get_batching_stats():
    model_id = request.args.get('model_id')
    version = request.args.get('version')

    if not model_id or not version:
        return jsonify(error_response(400, "Missing required parameters: model_id, version")), 400

    stats = inference_service.get_batching_stats(model_id, version)
    if stats:
        return jsonify(success_response(stats))
    return jsonify(error_response(404, "Batching engine not found or not enabled")), 404


@api_bp.route('/models/inference/batching/enabled', methods=['GET'])
def is_batching_enabled():
    model_id = request.args.get('model_id')
    version = request.args.get('version')

    if not model_id or not version:
        return jsonify(error_response(400, "Missing required parameters: model_id, version")), 400

    enabled = inference_service.is_batching_enabled(model_id, version)
    return jsonify(success_response({
        "model_id": model_id,
        "version": version,
        "batching_enabled": enabled
    }))


@api_bp.route('/models/deployments/<deploy_id>/healthcheck', methods=['POST'])
def perform_runtime_health_check(deploy_id: str):
    health_result = deployment_manager.perform_runtime_health_check(deploy_id)
    if health_result:
        return jsonify(success_response(health_result.to_dict()))
    return jsonify(error_response(404, "Deployment not found")), 404


@api_bp.route('/models/deployments/<deploy_id>/latency-check', methods=['POST'])
def perform_runtime_latency_check(deploy_id: str):
    data = request.get_json() or {}
    sample_count = data.get('sample_count', 5)

    latency_result = deployment_manager.perform_runtime_latency_check(deploy_id, sample_count)
    if latency_result:
        return jsonify(success_response(latency_result.to_dict()))
    return jsonify(error_response(404, "Deployment not found")), 404


@api_bp.route('/monitoring/queue/status', methods=['GET'])
def get_monitoring_queue_status():
    status = monitoring_manager.get_queue_status()
    return jsonify(success_response(status))


@api_bp.route('/monitoring/redis-status', methods=['GET'])
def get_monitoring_redis_status():
    redis_connected = monitoring_manager.check_redis_connection()
    queue_status = monitoring_manager.get_queue_status()
    return jsonify(success_response({
        "redis_connected": redis_connected,
        "use_redis": queue_status.get('use_redis', False),
        "queue_size": queue_status.get('queue_size', 0)
    }))


@api_bp.route('/monitoring/flush', methods=['POST'])
def flush_monitoring():
    model_id = request.args.get('model_id')

    monitoring_manager.flush(model_id)
    return jsonify(success_response(message="Monitoring buffer flushed"))


@api_bp.route('/models/deploy/details/<deploy_id>', methods=['GET'])
def get_deployment_details(deploy_id: str):
    details = deployment_manager.get_deployment_details(deploy_id)
    if details:
        return jsonify(success_response(details))
    return jsonify(error_response(404, "Deployment not found")), 404
