package com.company.dbstudio;

import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.EventBus;
import com.company.dbstudio.core.exception.GlobalExceptionHandler;
import com.company.dbstudio.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

public class DBStudioApplication extends Application {

    private static final Logger logger = LoggerFactory.getLogger(DBStudioApplication.class);
    private static final String APP_TITLE = "DBStudio - Database Management Client";
    private static final String APP_ICON = "/icons/app-icon.png";

    @Override
    public void start(Stage primaryStage) {
        Thread.setDefaultUncaughtExceptionHandler(new GlobalExceptionHandler());

        ApplicationContext.initialize();

        MainView mainView = new MainView();
        Scene scene = new Scene(mainView.getRoot(), 1400, 900);
        scene.getStylesheets().add(getClass().getResource("/css/dbstudio.css").toExternalForm());

        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(700);

        try (InputStream iconStream = getClass().getResourceAsStream(APP_ICON)) {
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception e) {
            logger.warn("Failed to load application icon", e);
        }

        primaryStage.setOnCloseRequest(event -> {
            logger.info("Shutting down DBStudio...");
            ApplicationContext.shutdown();
            EventBus.getInstance().shutdown();
        });

        primaryStage.show();
        logger.info("DBStudio started successfully");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
