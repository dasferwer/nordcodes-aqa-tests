package qa.nordcodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    protected static final String API_KEY = "qazWSXedc";
    protected WireMockServer external;
    private Process application;
    private Path applicationLog;
    private URI endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeAll
    void startServices() throws Exception {
        String jarProperty = System.getProperty("app.jar");
        if (jarProperty == null || !Files.isRegularFile(Path.of(jarProperty))) {
            throw new IllegalStateException("Укажите путь к приложению: -Dapp.jar=/path/to/application.jar");
        }
        external = new WireMockServer(wireMockConfig().bindAddress("127.0.0.1").dynamicPort());
        external.start();
        Path target = Path.of("target").toAbsolutePath();
        Files.createDirectories(target);
        applicationLog = target.resolve(getClass().getSimpleName() + "-application.log");
        try {
            application = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-Dsecret=" + API_KEY,
                    "-Dmock=http://127.0.0.1:" + external.port() + "/",
                    "-jar", Path.of(jarProperty).toAbsolutePath().toString(),
                    "--server.address=127.0.0.1", "--server.port=0")
                    .redirectErrorStream(true).redirectOutput(applicationLog.toFile()).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline && application.isAlive()) {
                String log = Files.readString(applicationLog);
                var port = PORT.matcher(log);
                if (port.find() && log.contains("Started AqaApplication")) {
                    endpoint = URI.create("http://127.0.0.1:" + port.group(1) + "/endpoint");
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Приложение не запустилось. Журнал: " + Files.readString(applicationLog));
        } catch (Exception failure) {
            stopServices();
            throw failure;
        }
    }

    @BeforeEach
    void resetExternalService() {
        external.resetAll();
    }

    @AfterAll
    void stopServices() throws InterruptedException {
        try {
            if (application != null && application.isAlive()) {
                application.destroy();
                if (!application.waitFor(5, TimeUnit.SECONDS)) {
                    application.destroyForcibly();
                    application.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            if (external != null) {
                external.stop();
            }
        }
    }

    @AfterEach
    void attachExternalRequests() throws java.io.IOException {
        var requests = external.getAllServeEvents().stream().map(event -> Map.of(
                "method", event.getRequest().getMethod().toString(),
                "url", event.getRequest().getUrl(),
                "body", event.getRequest().getBodyAsString())).toList();
        Allure.addAttachment("Запросы внешнего сервиса", "application/json",
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(requests), ".json");
    }

    protected String newToken() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    protected void stub(String path, int status, String body) {
        external.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    protected Reply send(String token, String action) {
        return send(token, action, API_KEY);
    }

    protected Reply send(String token, String action, String key) {
        List<String> fields = new ArrayList<>();
        if (token != null) fields.add("token=" + encode(token));
        if (action != null) fields.add("action=" + encode(action));
        return sendRaw("POST", String.join("&", fields), "application/x-www-form-urlencoded", key);
    }

    protected Reply sendRaw(String method, String body, String contentType, String key) {
        return Allure.step("Отправить " + method + " /endpoint: " + body, () -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (key != null) builder.header("X-Api-Key", key);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Allure.addAttachment("Ответ приложения", "text/plain",
                    "HTTP " + response.statusCode() + "\n" + response.body(), ".txt");
            return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                    response.body());
        });
    }

    protected void assertOk(Reply response) {
        Allure.step("Проверить успешный ответ", () -> {
            JsonNode json = response.json();
            assertAll(
                    () -> assertTrue(response.status() >= 200 && response.status() < 300,
                            "Ожидался успешный HTTP-статус, получен " + response.status()),
                    () -> assertTrue(response.contentType().startsWith("application/json"), "Ожидался JSON"),
                    () -> assertEquals("OK", json.path("result").asText(), response.body()));
        });
    }

    protected void assertError(Reply response) {
        Allure.step("Проверить отказ с понятной причиной", () -> {
            JsonNode json = response.json();
            assertAll(
                    () -> assertTrue(response.contentType().startsWith("application/json"), "Ожидался JSON"),
                    () -> assertEquals("ERROR", json.path("result").asText(), response.body()),
                    () -> assertTrue(json.path("message").isTextual()
                            && !json.path("message").asText().isBlank(), "Не указана причина отказа"));
        });
    }

    protected void verifyCall(String path, String token, int count) {
        Allure.step("Проверить передачу токена во внешний сервис " + path, () -> {
            external.verify(count, postRequestedFor(urlEqualTo(path))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withFormParam("token", equalTo(token)));
            assertEquals(count, external.findAll(postRequestedFor(urlEqualTo(path))).size(),
                    "На этот адрес поступили лишние запросы");
        });
    }

    protected void verifyNoExternalCalls() {
        Allure.step("Проверить отсутствие обращений во внешний сервис", () ->
                assertTrue(external.getAllServeEvents().isEmpty(), "Внешний сервис не должен вызываться"));
    }

    protected static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    protected record Reply(int status, String contentType, String body) {
        JsonNode json() {
            try {
                JsonNode value = JSON.readTree(body);
                assertNotNull(value, "Пустой JSON-ответ");
                assertTrue(value.isObject(), "Ответ должен быть JSON-объектом: " + body);
                return value;
            } catch (java.io.IOException e) {
                throw new AssertionError("Ответ не является корректным JSON: " + body, e);
            }
        }
    }
}
