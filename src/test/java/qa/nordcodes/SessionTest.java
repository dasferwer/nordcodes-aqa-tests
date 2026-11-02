package qa.nordcodes;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Epic("API управления сессиями")
@Feature("Вход, действие и выход")
class SessionTest extends ApiTestSupport {
    @Test
    @DisplayName("Одна сессия позволяет выполнять несколько действий")
    void repeatedActions() {
        String token = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        for (int i = 0; i < 3; i++) assertOk(send(token, "ACTION"));
        verifyCall("/auth", token, 1);
        verifyCall("/doAction", token, 3);
    }

    @Test
    @DisplayName("Пользователь входит, выполняет действие и выходит")
    void fullSession() {
        String token = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        assertOk(send(token, "ACTION"));
        assertOk(send(token, "LOGOUT"));
        external.resetRequests();
        assertError(send(token, "ACTION"));
        verifyNoExternalCalls();
    }

    @Test
    @DisplayName("Действие без предварительного входа отклоняется")
    void actionWithoutLogin() {
        assertError(send(newToken(), "ACTION"));
        verifyNoExternalCalls();
    }

    @Test
    @DisplayName("Вход передаёт токен на /auth с нужными заголовками")
    void loginContract() {
        String token = newToken();
        stub("/auth", 200, "not-json");
        assertOk(send(token, "LOGIN"));
        verifyCall("/auth", token, 1);
        external.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/doAction")));
    }

    @Test
    @DisplayName("Действие передаёт токен на /doAction с нужными заголовками")
    void actionContract() {
        String token = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "any body");
        assertOk(send(token, "LOGIN"));
        external.resetRequests();
        assertOk(send(token, "ACTION"));
        verifyCall("/doAction", token, 1);
        external.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/auth")));
    }

    @Test
    @DisplayName("Выход не обращается во внешний сервис")
    void logoutIsLocal() {
        String token = newToken();
        stub("/auth", 200, "");
        assertOk(send(token, "LOGIN"));
        external.resetAll();
        assertOk(send(token, "LOGOUT"));
        verifyNoExternalCalls();
    }

    @Test
    @DisplayName("После выхода пользователь может войти повторно")
    void loginAfterLogout() {
        String token = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        assertOk(send(token, "LOGOUT"));
        assertOk(send(token, "LOGIN"));
        assertOk(send(token, "ACTION"));
        verifyCall("/auth", token, 2);
    }

    @Test
    @DisplayName("Выход одного пользователя не завершает сессию другого")
    void independentSessions() {
        String first = newToken();
        String second = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(first, "LOGIN"));
        assertOk(send(second, "LOGIN"));
        assertOk(send(first, "LOGOUT"));
        external.resetRequests();
        assertError(send(first, "ACTION"));
        assertOk(send(second, "ACTION"));
        verifyCall("/doAction", second, 1);
    }
}
