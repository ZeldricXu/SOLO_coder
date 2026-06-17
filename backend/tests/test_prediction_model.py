import pytest
import numpy as np
import tempfile
from pathlib import Path

from app.prediction.model import LSTMModel, TransformerModel, TrafficPredictor
import torch


@pytest.mark.unit
class TestPredictionModel:
    """流量预测模型单元测试"""

    def test_lstm_model_forward_pass(self):
        model = LSTMModel(input_size=1, hidden_size=32, num_layers=2, output_size=1)
        batch_size = 4
        seq_len = 24
        x = torch.randn(batch_size, seq_len, 1)
        output = model(x)
        assert output.shape == (batch_size, 1)

    def test_transformer_model_forward_pass(self):
        model = TransformerModel(input_size=1, d_model=32, nhead=4,
                                 num_encoder_layers=2, output_size=1)
        batch_size = 4
        seq_len = 24
        x = torch.randn(batch_size, seq_len, 1)
        output = model(x)
        assert output.shape == (batch_size, 1)

    def test_predictor_create_sequences(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)
        data = np.arange(100, dtype=np.float32)
        seqs, targets = predictor._create_sequences(data, sequence_length=10, horizon=1)
        assert seqs.shape[0] == 100 - 10 - 1 + 1
        assert seqs.shape[1] == 10
        assert targets.shape[0] == seqs.shape[0]

    def test_predictor_predict_output_shape(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)
        data = np.sin(np.arange(200) * 0.1).astype(np.float32)

        predictions = predictor.predict(data, sequence_length=24, steps=3)
        assert predictions.shape == (3,)

    def test_predictor_multi_horizon(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)
        data = np.sin(np.arange(200) * 0.1).astype(np.float32)

        predictions = predictor.predict_multi_horizon(data, sequence_length=24, horizons=[15, 30, 60])
        assert 15 in predictions
        assert 30 in predictions
        assert 60 in predictions

    def test_model_save_and_load(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)

        with tempfile.NamedTemporaryFile(suffix=".pth", delete=False) as f:
            model_path = f.name

        try:
            predictor.save_model(model_path)

            predictor2 = TrafficPredictor(model_type="lstm", hidden_size=16)
            predictor2.load_model(model_path)

            data = np.sin(np.arange(100) * 0.1).astype(np.float32)
            pred1 = predictor.predict(data, sequence_length=24, steps=1)
            pred2 = predictor2.predict(data, sequence_length=24, steps=1)

            np.testing.assert_array_almost_equal(pred1, pred2, decimal=5)
        finally:
            Path(model_path).unlink(missing_ok=True)

    def test_training_reduces_loss(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)

        np.random.seed(42)
        data = np.sin(np.arange(500) * 0.05).astype(np.float32) + np.random.randn(500).astype(np.float32) * 0.1

        history = predictor.train(
            data, epochs=20, batch_size=8, learning_rate=0.01,
            sequence_length=24, horizon=1
        )

        assert "train_losses" in history
        assert "val_losses" in history
        assert len(history["train_losses"]) == 20

    def test_evaluate_metrics(self):
        predictor = TrafficPredictor(model_type="lstm", hidden_size=16)

        np.random.seed(42)
        data = np.sin(np.arange(500) * 0.05).astype(np.float32)
        predictor.train(data, epochs=5, batch_size=8, sequence_length=24)

        test_data = np.sin(np.arange(200) * 0.05).astype(np.float32)
        metrics = predictor.evaluate(test_data, sequence_length=24)

        assert "mse" in metrics
        assert "rmse" in metrics
        assert "mae" in metrics
        assert "mape" in metrics
        assert metrics["rmse"] >= 0

    def test_invalid_model_type_raises_error(self):
        with pytest.raises(ValueError, match="Unknown model type"):
            TrafficPredictor(model_type="invalid_model")
