package qa.nordcodes;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE;

@Epic("API управления сессиями")
@Feature("Взаимодействие с внешним сервисом")
class ExternalServiceTest extends ApiTestSupport {
    static Stream<Arguments> successfulReplies() {
        return Stream.of("LOGIN", "ACTION").flatMap(action -> Stream.of(
                Arguments.of(action, 200, "", "пустое тело"),
                Arguments.of(action, 201, "not-json", "произвольный текст"),
                Arguments.of(action, 202, "{\"result\":\"ERROR\"}", "JSON с ошибкой в теле"),
                Arguments.of(action, 204, "", "ответ без содержимого")));
    }

    @ParameterizedTest(name = "{0}, HTTP {1}, {3}")
    @MethodSource("successfulReplies")
    @DisplayName("Решение об успехе зависит от кода внешнего ответа, а не от тела")
    void ignoresExternalResponseBody(String action, int status, String body, String label) {
        String token = newToken();
        stub("/auth", 200, "");
        if (action.equals("ACTION")) assertOk(send(token, "LOGIN"));
        String path = action.equals("LOGIN") ? "/auth" : "/doAction";
        external.resetAll();
        stub(path, status, body);
        assertOk(send(token, action));
        verifyCall(path, token, 1);
    }

    @ParameterizedTest(name = "Отказ /auth: HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    @DisplayName("Неуспешный вход не создаёт сессию; повтор после восстановления разрешён")
    void failedLoginDoesNotCreateSession(int status) {
        String token = newToken();
        stub("/auth", status, "external failure");
        assertError(send(token, "LOGIN"));
        verifyCall("/auth", token, 1);
        external.resetAll();
        assertError(send(token, "ACTION"));
        verifyNoExternalCalls();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        assertOk(send(token, "ACTION"));
    }

    @ParameterizedTest(name = "Отказ /doAction: HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    @DisplayName("Ошибка действия возвращается клиенту и не завершает сессию")
    void failedActionKeepsSession(int status) {
        String token = newToken();
        stub("/auth", 200, "");
        assertOk(send(token, "LOGIN"));
        external.resetAll();
        stub("/doAction", status, "external failure");
        assertError(send(token, "ACTION"));
        verifyCall("/doAction", token, 1);
        external.resetAll();
        stub("/doAction", 200, "");
        assertOk(send(token, "ACTION"));
        verifyCall("/doAction", token, 1);
    }

    @Test
    @DisplayName("Разрыв соединения при входе не создаёт сессию и не блокирует повторный вход")
    void connectionFailureAtLogin() {
        String token = newToken();
        external.stubFor(post(urlEqualTo("/auth")).willReturn(aResponse().withFault(EMPTY_RESPONSE)));
        assertError(send(token, "LOGIN"));
        external.resetAll();
        assertError(send(token, "ACTION"));
        verifyNoExternalCalls();
        stub("/auth", 200, "");
        assertOk(send(token, "LOGIN"));
    }

    @Test
    @DisplayName("Таймаут внешнего сервиса возвращает ошибку; повторный вход после восстановления работает")
    void externalTimeout() {
        String token = newToken();
        external.stubFor(post(urlEqualTo("/auth"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(12000)));
        assertError(send(token, "LOGIN"));
        external.resetAll();
        assertError(send(token, "ACTION"));
        verifyNoExternalCalls();
        stub("/auth", 200, "");
        assertOk(send(token, "LOGIN"));
    }
}
