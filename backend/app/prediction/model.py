import torch
import torch.nn as nn
import numpy as np
from typing import Tuple, Optional
import logging

logger = logging.getLogger(__name__)


class LSTMModel(nn.Module):
    def __init__(self, input_size: int = 1, hidden_size: int = 64,
                 num_layers: int = 2, output_size: int = 1,
                 dropout: float = 0.2):
        super(LSTMModel, self).__init__()
        self.hidden_size = hidden_size
        self.num_layers = num_layers

        self.lstm = nn.LSTM(
            input_size=input_size,
            hidden_size=hidden_size,
            num_layers=num_layers,
            batch_first=True,
            dropout=dropout if num_layers > 1 else 0,
        )

        self.fc = nn.Sequential(
            nn.Linear(hidden_size, hidden_size // 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden_size // 2, output_size),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        batch_size = x.size(0)

        h0 = torch.zeros(self.num_layers, batch_size, self.hidden_size).to(x.device)
        c0 = torch.zeros(self.num_layers, batch_size, self.hidden_size).to(x.device)

        out, _ = self.lstm(x, (h0, c0))
        out = self.fc(out[:, -1, :])

        return out


class TransformerModel(nn.Module):
    def __init__(self, input_size: int = 1, d_model: int = 64,
                 nhead: int = 4, num_encoder_layers: int = 2,
                 dim_feedforward: int = 256, output_size: int = 1,
                 dropout: float = 0.1, max_len: int = 5000):
        super(TransformerModel, self).__init__()

        self.d_model = d_model
        self.embedding = nn.Linear(input_size, d_model)
        self.pos_encoder = nn.Parameter(torch.zeros(max_len, d_model))

        encoder_layers = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True,
        )
        self.transformer_encoder = nn.TransformerEncoder(
            encoder_layers, num_encoder_layers
        )

        self.fc = nn.Sequential(
            nn.Linear(d_model, d_model // 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(d_model // 2, output_size),
        )

        self._init_weights()

    def _init_weights(self):
        nn.init.uniform_(self.pos_encoder, -0.1, 0.1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        batch_size, seq_len, _ = x.size()

        x = self.embedding(x) * np.sqrt(self.d_model)
        x = x + self.pos_encoder[:seq_len, :].unsqueeze(0)

        x = self.transformer_encoder(x)
        out = self.fc(x[:, -1, :])

        return out


class TrafficPredictor:
    def __init__(self, model_type: str = "lstm", input_size: int = 1,
                 hidden_size: int = 64, num_layers: int = 2,
                 output_size: int = 1, device: str = None):
        self.model_type = model_type
        self.input_size = input_size
        self.output_size = output_size
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")

        if model_type == "lstm":
            self.model = LSTMModel(
                input_size=input_size,
                hidden_size=hidden_size,
                num_layers=num_layers,
                output_size=output_size,
            )
        elif model_type == "transformer":
            self.model = TransformerModel(
                input_size=input_size,
                d_model=hidden_size,
                num_encoder_layers=num_layers,
                output_size=output_size,
            )
        else:
            raise ValueError(f"Unknown model type: {model_type}")

        self.model.to(self.device)
        self.scaler = None

    def train(self, train_data: np.ndarray, epochs: int = 100,
              batch_size: int = 32, learning_rate: float = 0.001,
              validation_split: float = 0.2, sequence_length: int = 24,
              horizon: int = 1) -> dict:
        self.model.train()

        train_seqs, train_targets = self._create_sequences(
            train_data, sequence_length, horizon
        )

        split_idx = int(len(train_seqs) * (1 - validation_split))
        X_train = train_seqs[:split_idx]
        y_train = train_targets[:split_idx]
        X_val = train_seqs[split_idx:]
        y_val = train_targets[split_idx:]

        X_train_tensor = torch.FloatTensor(X_train).to(self.device)
        y_train_tensor = torch.FloatTensor(y_train).to(self.device)
        X_val_tensor = torch.FloatTensor(X_val).to(self.device)
        y_val_tensor = torch.FloatTensor(y_val).to(self.device)

        criterion = nn.MSELoss()
        optimizer = torch.optim.Adam(self.model.parameters(), lr=learning_rate)

        train_losses = []
        val_losses = []

        for epoch in range(epochs):
            total_train_loss = 0
            self.model.train()

            for i in range(0, len(X_train_tensor), batch_size):
                batch_X = X_train_tensor[i:i + batch_size]
                batch_y = y_train_tensor[i:i + batch_size]

                optimizer.zero_grad()
                outputs = self.model(batch_X)
                loss = criterion(outputs, batch_y)
                loss.backward()
                optimizer.step()

                total_train_loss += loss.item()

            avg_train_loss = total_train_loss / (len(X_train_tensor) // batch_size + 1)
            train_losses.append(avg_train_loss)

            with torch.no_grad():
                self.model.eval()
                val_outputs = self.model(X_val_tensor)
                val_loss = criterion(val_outputs, y_val_tensor)
                val_losses.append(val_loss.item())

            if (epoch + 1) % 10 == 0:
                logger.info(f"Epoch {epoch+1}/{epochs}, Train Loss: {avg_train_loss:.6f}, Val Loss: {val_loss.item():.6f}")

        return {
            "train_losses": train_losses,
            "val_losses": val_losses,
            "final_train_loss": train_losses[-1],
            "final_val_loss": val_losses[-1],
        }

    def predict(self, data: np.ndarray, sequence_length: int = 24,
                horizon: int = 1, steps: int = 1) -> np.ndarray:
        self.model.eval()

        predictions = []
        current_sequence = data[-sequence_length:].copy()

        with torch.no_grad():
            for _ in range(steps):
                x = current_sequence[-sequence_length:]
                x_tensor = torch.FloatTensor(x).unsqueeze(0).unsqueeze(-1).to(self.device)

                pred = self.model(x_tensor)
                pred_value = pred.cpu().numpy()[0, 0]
                predictions.append(pred_value)

                current_sequence = np.roll(current_sequence, -1)
                current_sequence[-1] = pred_value

        return np.array(predictions)

    def predict_multi_horizon(self, data: np.ndarray, sequence_length: int = 24,
                              horizons: list = None) -> dict:
        if horizons is None:
            horizons = [15, 30, 60]

        predictions = {}
        for horizon in horizons:
            steps = max(1, horizon // 5)
            preds = self.predict(data, sequence_length, horizon, steps)
            predictions[horizon] = preds[-1] if len(preds) > 0 else None

        return predictions

    def _create_sequences(self, data: np.ndarray, sequence_length: int,
                          horizon: int = 1) -> Tuple[np.ndarray, np.ndarray]:
        sequences = []
        targets = []

        for i in range(len(data) - sequence_length - horizon + 1):
            sequences.append(data[i:i + sequence_length])
            targets.append(data[i + sequence_length + horizon - 1])

        return np.array(sequences), np.array(targets)

    def save_model(self, path: str):
        torch.save({
            'model_state_dict': self.model.state_dict(),
            'model_type': self.model_type,
            'input_size': self.input_size,
            'output_size': self.output_size,
        }, path)
        logger.info(f"Model saved to {path}")

    def load_model(self, path: str):
        checkpoint = torch.load(path, map_location=self.device)
        self.model.load_state_dict(checkpoint['model_state_dict'])
        self.model.eval()
        logger.info(f"Model loaded from {path}")

    def evaluate(self, test_data: np.ndarray, sequence_length: int = 24,
                 horizon: int = 1) -> dict:
        self.model.eval()

        test_seqs, test_targets = self._create_sequences(
            test_data, sequence_length, horizon
        )

        X_test = torch.FloatTensor(test_seqs).unsqueeze(-1).to(self.device)
        y_test = torch.FloatTensor(test_targets).to(self.device)

        with torch.no_grad():
            predictions = self.model(X_test)

        criterion = nn.MSELoss()
        mse = criterion(predictions, y_test).item()
        rmse = np.sqrt(mse)

        mae = nn.L1Loss()(predictions, y_test).item()

        y_test_np = y_test.cpu().numpy()
        pred_np = predictions.cpu().numpy()
        mape = np.mean(np.abs((y_test_np - pred_np) / (y_test_np + 1e-8))) * 100

        return {
            "mse": mse,
            "rmse": rmse,
            "mae": mae,
            "mape": mape,
        }
