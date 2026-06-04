package com.flowplatform.integration;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import com.flowplatform.test.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("表单拖拽布局回归测试")
public class FormDragAndDropTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    static BrowserWebDriverContainer<?> chromeContainer = new BrowserWebDriverContainer<>("selenium/standalone-chrome:120.0")
            .withCapabilities(new ChromeOptions().addArguments("--headless=new", "--disable-gpu", "--window-size=1920,1080"))
            .withStartupTimeout(Duration.ofMinutes(2));

    private RemoteWebDriver driver;

    @BeforeAll
    static void beforeAll() {
        chromeContainer.start();
    }

    @AfterAll
    static void afterAll() {
        if (chromeContainer != null && chromeContainer.isRunning()) {
            chromeContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        driver = new RemoteWebDriver(chromeContainer.getSeleniumAddress(), new ChromeOptions());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        driver.manage().window().setSize(new Dimension(1920, 1080));
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    private String getDesignerUrl() {
        return "http://host.testcontainers.internal:" + port + "/form/designer";
    }

    @Test
    @DisplayName("拖拽后DOM位置正确断言")
    void testDragAndDropPositions() throws Exception {
        driver.get(getDesignerUrl());

        Thread.sleep(2000);

        List<WebElement> fieldCards = driver.findElements(By.cssSelector(".field-card"));
        assertTrue(fieldCards.size() >= 3, "字段卡片至少有3个");

        WebElement canvas = driver.findElement(By.id("form-canvas"));

        WebElement textField = fieldCards.stream()
                .filter(e -> e.getText().contains("单行文本"))
                .findFirst().orElseThrow();
        WebElement numberField = fieldCards.stream()
                .filter(e -> e.getText().contains("数字"))
                .findFirst().orElseThrow();
        WebElement selectField = fieldCards.stream()
                .filter(e -> e.getText().contains("下拉选择"))
                .findFirst().orElseThrow();

        Actions actions = new Actions(driver);
        actions.clickAndHold(textField)
                .moveToElement(canvas)
                .release()
                .perform();
        Thread.sleep(500);

        actions.clickAndHold(numberField)
                .moveToElement(canvas)
                .release()
                .perform();
        Thread.sleep(500);

        actions.clickAndHold(selectField)
                .moveToElement(canvas)
                .release()
                .perform();
        Thread.sleep(500);

        List<WebElement> canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        assertEquals(3, canvasFields.size(), "画布应有3个控件");

        assertEquals("单行文本", canvasFields.get(0).getAttribute("data-label"));
        assertEquals("数字", canvasFields.get(1).getAttribute("data-label"));
        assertEquals("下拉选择", canvasFields.get(2).getAttribute("data-label"));

        Point p1 = canvasFields.get(0).getLocation();
        Point p2 = canvasFields.get(1).getLocation();
        Point p3 = canvasFields.get(2).getLocation();

        assertTrue(p1.getY() < p2.getY(), "第一个控件Y坐标应小于第二个");
        assertTrue(p2.getY() < p3.getY(), "第二个控件Y坐标应小于第三个");

        assertEquals(p1.getX(), p2.getX(), "控件X坐标应相同");
        assertEquals(p2.getX(), p3.getX(), "控件X坐标应相同");
    }

    @Test
    @DisplayName("拖拽排序后位置正确")
    void testDragReorder() throws Exception {
        driver.get(getDesignerUrl());
        Thread.sleep(2000);

        List<WebElement> fieldCards = driver.findElements(By.cssSelector(".field-card"));
        WebElement canvas = driver.findElement(By.id("form-canvas"));

        Actions actions = new Actions(driver);

        for (int i = 0; i < 3; i++) {
            actions.clickAndHold(fieldCards.get(i))
                    .moveToElement(canvas)
                    .release()
                    .perform();
            Thread.sleep(300);
        }

        List<WebElement> canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        assertEquals(3, canvasFields.size());

        String firstBefore = canvasFields.get(0).getAttribute("data-field-key");
        String thirdBefore = canvasFields.get(2).getAttribute("data-field-key");

        WebElement firstField = canvasFields.get(0);
        WebElement thirdField = canvasFields.get(2);

        actions.dragAndDropBy(firstField, 0, 200)
                .perform();
        Thread.sleep(500);

        canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        String firstAfter = canvasFields.get(0).getAttribute("data-field-key");
        String lastAfter = canvasFields.get(2).getAttribute("data-field-key");

        assertNotEquals(firstBefore, firstAfter, "拖拽后第一个控件应变化");
        assertEquals(firstBefore, lastAfter, "原第一个应移到最后");
    }

    @Test
    @DisplayName("滚动容器内拖拽位置正确")
    void testDragWithScroll() throws Exception {
        driver.get(getDesignerUrl());
        Thread.sleep(2000);

        List<WebElement> fieldCards = driver.findElements(By.cssSelector(".field-card"));
        WebElement canvas = driver.findElement(By.id("form-canvas"));

        Actions actions = new Actions(driver);

        for (int i = 0; i < 8; i++) {
            actions.clickAndHold(fieldCards.get(i % fieldCards.size()))
                    .moveToElement(canvas)
                    .release()
                    .perform();
            Thread.sleep(200);
        }

        List<WebElement> canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        assertEquals(8, canvasFields.size(), "画布应有8个控件");

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("document.getElementById('form-canvas').parentElement.scrollTop = 500;");
        Thread.sleep(300);

        WebElement fieldToDrag = canvasFields.get(1);
        int originalY = fieldToDrag.getLocation().getY();

        actions.dragAndDropBy(fieldToDrag, 0, 150)
                .perform();
        Thread.sleep(500);

        canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        WebElement afterDrag = canvasFields.get(2);

        int newY = afterDrag.getLocation().getY();
        assertTrue(newY > originalY, "拖拽后Y坐标应增加");

        for (int i = 0; i < canvasFields.size() - 1; i++) {
            int y1 = canvasFields.get(i).getLocation().getY();
            int y2 = canvasFields.get(i + 1).getLocation().getY();
            assertTrue(y2 > y1, "控件" + i + "和" + (i+1) + "不应重叠");
        }
    }

    @Test
    @DisplayName("Delta增量位移计算验证")
    void testDeltaDisplacement() throws Exception {
        driver.get(getDesignerUrl());
        Thread.sleep(2000);

        List<WebElement> fieldCards = driver.findElements(By.cssSelector(".field-card"));
        WebElement canvas = driver.findElement(By.id("form-canvas"));

        Actions actions = new Actions(driver);
        for (int i = 0; i < 4; i++) {
            actions.clickAndHold(fieldCards.get(i))
                    .moveToElement(canvas)
                    .release()
                    .perform();
            Thread.sleep(200);
        }

        List<WebElement> canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        assertEquals(4, canvasFields.size());

        int[] yPositions = new int[4];
        for (int i = 0; i < 4; i++) {
            yPositions[i] = canvasFields.get(i).getLocation().getY();
        }

        WebElement secondField = canvasFields.get(1);
        actions.dragAndDropBy(secondField, 0, 200)
                .perform();
        Thread.sleep(500);

        canvasFields = driver.findElements(By.cssSelector("#form-canvas .canvas-field"));
        int[] newYPositions = new int[4];
        for (int i = 0; i < 4; i++) {
            newYPositions[i] = canvasFields.get(i).getLocation().getY();
        }

        assertEquals(yPositions[0], newYPositions[0], "第一个控件位置应不变");
        assertEquals(yPositions[1], newYPositions[3], "原第二个应移到最后");
        assertEquals(yPositions[2], newYPositions[1], "原第三个应前移一位");
        assertEquals(yPositions[3], newYPositions[2], "原第四个应前移一位");
    }
}
