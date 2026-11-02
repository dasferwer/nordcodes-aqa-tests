package qa.nordcodes;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("API управления сессиями")
@Feature("Защита API-ключом")
class AuthenticationTest extends ApiTestSupport {
    static Stream<Arguments> invalidLoginKeys() {
        return Stream.of(
                Arguments.of("ключ отсутствует", null),
                Arguments.of("пустой ключ", ""),
                Arguments.of("неверный ключ", "incorrect-key"));
    }

    @ParameterizedTest(name = "Вход нового пользователя: {0}")
    @MethodSource("invalidLoginKeys")
    @DisplayName("Вход без API-ключа не создаёт сессию")
    void unauthorizedLoginDoesNotCreateSession(String label, String key) {
        String token = newToken();
        stub("/auth", 200, "");
        Reply denied = send(token, "LOGIN", key);
        assertError(denied);
        assertTrue(denied.status() == 401 || denied.status() == 403,
                "Ожидался отказ в аутентификации HTTP 401 или 403");
        verifyNoExternalCalls();
        assertError(send(token, "ACTION"));
        verifyNoExternalCalls();
        assertOk(send(token, "LOGIN"));
        verifyCall("/auth", token, 1);
    }

    static Stream<Arguments> invalidKeys() {
        return Stream.of("LOGIN", "ACTION", "LOGOUT").flatMap(action -> Stream.of(
                Arguments.of(action, "ключ отсутствует", null),
                Arguments.of(action, "пустой ключ", ""),
                Arguments.of(action, "неверный ключ", "incorrect-key"),
                Arguments.of(action, "неверный регистр", "QAZwsxEDC")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidKeys")
    @DisplayName("Без правильного API-ключа операция отклоняется и сессия сохраняется")
    void rejectsUnauthorizedRequest(String action, String label, String key) {
        String token = newToken();
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        external.resetRequests();

        Reply denied = send(token, action, key);
        assertError(denied);
        assertTrue(denied.status() == 401 || denied.status() == 403,
                "Ожидался отказ в аутентификации HTTP 401 или 403");
        verifyNoExternalCalls();

        assertOk(send(token, "ACTION"));
        verifyCall("/doAction", token, 1);
    }
}
