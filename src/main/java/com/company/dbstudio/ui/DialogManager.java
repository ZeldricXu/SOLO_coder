package com.company.dbstudio.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.util.Optional;

public class DialogManager {

    public DialogManager() {}

    public void showInfo(String title, String message) {
        showAlert(Alert.AlertType.INFORMATION, title, null, message, null);
    }

    public void showInfo(String title, String header, String message) {
        showAlert(Alert.AlertType.INFORMATION, title, header, message, null);
    }

    public void showWarning(String title, String message) {
        showAlert(Alert.AlertType.WARNING, title, null, message, null);
    }

    public void showWarning(String title, String header, String message) {
        showAlert(Alert.AlertType.WARNING, title, header, message, null);
    }

    public void showError(String title, String message) {
        showAlert(Alert.AlertType.ERROR, title, null, message, null);
    }

    public void showError(String title, String header, String message) {
        showAlert(Alert.AlertType.ERROR, title, header, message, null);
    }

    public boolean showConfirmation(String title, String message) {
        return showConfirmation(title, null, message, null);
    }

    public boolean showConfirmation(String title, String header, String message) {
        return showConfirmation(title, header, message, null);
    }

    public boolean showConfirmation(String title, String header, String message, Window owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (owner != null) {
            alert.initOwner(owner);
        }
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    public Optional<String> showInputDialog(String title, String message) {
        return showInputDialog(title, null, message, "");
    }

    public Optional<String> showInputDialog(String title, String message, String defaultValue) {
        return showInputDialog(title, null, message, defaultValue);
    }

    public Optional<String> showInputDialog(String title, String header, String message, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(message);
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return dialog.showAndWait();
    }

    private void showAlert(Alert.AlertType type, String title, String header, String message, Window owner) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }
}
