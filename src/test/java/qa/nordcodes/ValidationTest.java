package qa.nordcodes;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

@Epic("API управления сессиями")
@Feature("Проверка входных данных")
class ValidationTest extends ApiTestSupport {
    static Stream<Arguments> validTokens() {
        return Stream.of(
                Arguments.of("32 цифры", "01234567890123456789012345678901"),
                Arguments.of("буквы A-F", "ABCDEF".repeat(5) + "AB"),
                Arguments.of("весь латинский алфавит", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"),
                Arguments.of("буква G на левой границе", "G" + "A".repeat(31)),
                Arguments.of("буква Z на правой границе", "A".repeat(31) + "Z"));
    }

    @ParameterizedTest(name = "Допустимый токен: {0}")
    @MethodSource("validTokens")
    @DisplayName("LOGIN принимает токены из A-Z и 0-9 длиной 32 символа")
    void acceptsValidToken(String label, String token) {
        stub("/auth", 200, "");
        stub("/doAction", 200, "");
        assertOk(send(token, "LOGIN"));
        verifyCall("/auth", token, 1);
        assertOk(send(token, "ACTION"));
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                Arguments.of("параметр отсутствует", null),
                Arguments.of("пустая строка", ""),
                Arguments.of("31 символ", "A".repeat(31)),
                Arguments.of("33 символа", "A".repeat(33)),
                Arguments.of("строчная буква", "a" + "A".repeat(31)),
                Arguments.of("пробел", " " + "A".repeat(31)),
                Arguments.of("знак плюс", "+" + "A".repeat(31)),
                Arguments.of("подчёркивание", "_" + "A".repeat(31)),
                Arguments.of("кириллическая А", "А" + "A".repeat(31)),
                Arguments.of("перевод строки", "A".repeat(31) + "\n"),
                Arguments.of("амперсанд", "&" + "A".repeat(31)));
    }

    @ParameterizedTest(name = "Недопустимый токен: {0}")
    @MethodSource("invalidTokens")
    @DisplayName("Ошибочный токен отклоняется до обращения во внешний сервис")
    void rejectsInvalidToken(String label, String token) {
        stub("/auth", 200, "");
        assertError(send(token, "LOGIN"));
        verifyNoExternalCalls();
    }

    static Stream<Arguments> invalidActions() {
        return Stream.of(
                Arguments.of("параметр отсутствует", null),
                Arguments.of("пустая строка", ""),
                Arguments.of("неизвестное действие", "DELETE"),
                Arguments.of("строчные буквы", "login"));
    }

    @ParameterizedTest(name = "Недопустимое действие: {0}")
    @MethodSource("invalidActions")
    @DisplayName("Ошибочное действие отклоняется без внешних запросов")
    void rejectsInvalidAction(String label, String action) {
        stub("/auth", 200, "");
        assertError(send(newToken(), action));
        verifyNoExternalCalls();
    }

    @ParameterizedTest(name = "Метод {0} не выполняет LOGIN")
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH"})
    @DisplayName("Эндпоинт принимает только POST")
    void rejectsOtherMethods(String method) {
        String token = newToken();
        stub("/auth", 200, "");
        Reply reply = sendRaw(method, "token=" + token + "&action=LOGIN",
                "application/x-www-form-urlencoded", API_KEY);
        org.junit.jupiter.api.Assertions.assertTrue(reply.status() >= 400 && reply.status() < 500,
                "Ожидался отказ HTTP 4xx, получен " + reply.status());
        verifyNoExternalCalls();
    }

    @ParameterizedTest(name = "Тип тела {0} не выполняет LOGIN")
    @ValueSource(strings = {"application/json", "text/plain"})
    @DisplayName("Запрос с неподдерживаемым типом тела отклоняется")
    void rejectsOtherContentTypes(String contentType) {
        String token = newToken();
        stub("/auth", 200, "");
        Reply reply = sendRaw("POST", "{\"token\":\"" + token + "\",\"action\":\"LOGIN\"}",
                contentType, API_KEY);
        org.junit.jupiter.api.Assertions.assertTrue(reply.status() >= 400 && reply.status() < 500,
                "Ожидался отказ HTTP 4xx, получен " + reply.status());
        verifyNoExternalCalls();
    }
}
