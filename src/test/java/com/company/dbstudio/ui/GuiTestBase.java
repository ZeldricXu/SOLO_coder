package com.company.dbstudio.ui;

import com.company.dbstudio.test.BaseTest;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testfx.api.FxToolkit;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.concurrent.TimeoutException;

public abstract class GuiTestBase extends BaseTest {

    @BeforeEach
    public void setUp() throws Exception {
        if (!FxToolkit.isFXApplicationThreadRunning()) {
            FxToolkit.registerPrimaryStage();
        }
    }

    @AfterEach
    public void tearDown() throws TimeoutException {
        FxToolkit.cleanupStages();
    }

    protected Button findButton(String query) {
        return lookup(query).queryAs(Button.class);
    }

    protected Button findButtonById(String id) {
        return lookup("#" + id).queryAs(Button.class);
    }

    protected TextField findTextField(String query) {
        return lookup(query).queryAs(TextField.class);
    }

    protected TextField findTextFieldById(String id) {
        return lookup("#" + id).queryAs(TextField.class);
    }

    protected TextArea findTextAreaById(String id) {
        return lookup("#" + id).queryAs(TextArea.class);
    }

    protected TreeView<?> findTreeViewById(String id) {
        return lookup("#" + id).queryAs(TreeView.class);
    }

    protected TabPane findTabPaneById(String id) {
        return lookup("#" + id).queryAs(TabPane.class);
    }

    protected TableView<?> findTableViewById(String id) {
        return lookup("#" + id).queryAs(TableView.class);
    }

    protected ComboBox<?> findComboBoxById(String id) {
        return lookup("#" + id).queryAs(ComboBox.class);
    }

    protected Label findLabelById(String id) {
        return lookup("#" + id).queryAs(Label.class);
    }

    @SuppressWarnings("unchecked")
    protected <T extends Node> T findById(String id) {
        return (T) lookup("#" + id).query();
    }

    protected void setTextFieldText(String id, String text) {
        javafx.application.Platform.runLater(() -> {
            TextField field = findTextFieldById(id);
            if (field != null) {
                field.setText(text);
            }
        });
    }

    protected void clickButton(String id) {
        javafx.application.Platform.runLater(() -> {
            Button btn = findButtonById(id);
            if (btn != null && btn.isDisabled() == false) {
                btn.fire();
            }
        });
    }

    protected void selectTab(TabPane tabPane, int index) {
        javafx.application.Platform.runLater(() -> {
            if (tabPane != null && index >= 0 && index < tabPane.getTabs().size()) {
                tabPane.getSelectionModel().select(index);
            }
        });
    }

    protected void waitForUiUpdate() throws Exception {
        Thread.sleep(500);
    }

    protected void verifyNodeExists(String id) {
        Node node = findById(id);
        assert node != null : "Node with id '" + id + "' not found";
    }
}
