import pytest
from model_registry import (
    ModelRegistrationRequest,
    ModelVersionCreateRequest,
    StageTransitionRequest,
    ModelStage,
    ModelFramework,
    model_registry_service,
)


def test_register_model():
    request = ModelRegistrationRequest(
        name="test-model",
        display_name="测试模型",
        task_type="text-generation",
        description="用于测试的模型",
    )
    model = model_registry_service.register_model(request)
    assert model.name == "test-model"
    assert model.model_id.startswith("mod_")


def test_create_version():
    model_req = ModelRegistrationRequest(
        name="version-test-model",
        task_type="classification",
    )
    model = model_registry_service.register_model(model_req)

    ver_req = ModelVersionCreateRequest(
        model_id=model.model_id,
        description="版本1.0.0",
        framework=ModelFramework.PYTORCH,
    )
    version = model_registry_service.create_model_version(ver_req)
    assert version.version == "0.1.0"


def test_stage_transition():
    model_req = ModelRegistrationRequest(
        name="stage-test-model",
        task_type="generation",
    )
    model = model_registry_service.register_model(model_req)

    ver_req = ModelVersionCreateRequest(model_id=model.model_id)
    version = model_registry_service.create_model_version(ver_req)

    transition_req = StageTransitionRequest(
        model_id=model.model_id,
        version=version.version,
        target_stage=ModelStage.STAGING,
        comment="部署到测试环境",
    )
    updated = model_registry_service.transition_stage(transition_req)
    assert updated.stage == ModelStage.STAGING
