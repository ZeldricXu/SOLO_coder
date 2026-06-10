package com.cardgame.netty.validation;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Message Validation Chain Tests")
class MessageValidationChainTest {

    private MessageValidationChain validationChain;

    @BeforeEach
    void setUp() {
        validationChain = new MessageValidationChain();
    }

    @Test
    @DisplayName("Validation chain should initialize with all validators")
    void chain_ShouldInitializeWithAllValidators() {
        assertThat(validationChain.getValidatorCount()).isEqualTo(3);
        assertThat(validationChain.getValidators()).hasSize(3);
    }

    @Nested
    @DisplayName("LengthValidator Tests")
    class LengthValidatorTests {

        @Test
        @DisplayName("Null message should fail")
        void validate_NullMessage_ShouldFail() {
            ValidationResult result = validationChain.validate(null);
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("Message with oversized data should fail")
        void validate_OversizedData_ShouldFail() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 11 * 1024 * 1024; i++) {
                sb.append('x');
            }
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData(sb.toString());

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(413);
            assertThat(result.getFirstError()).contains("too large");
        }

        @Test
        @DisplayName("Message with long playerId should fail")
        void validate_LongPlayerId_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setPlayerId("a".repeat(65));

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("Valid message should pass length validation")
        void validate_ValidMessage_ShouldPass() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("RequiredFieldValidator Tests")
    class RequiredFieldValidatorTests {

        @Test
        @DisplayName("Message without type should fail")
        void validate_NoType_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setType(null);

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getFirstError()).contains("type");
        }

        @Test
        @DisplayName("Message without timestamp should fail")
        void validate_NoTimestamp_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setTimestamp(0);

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getFirstError()).contains("timestamp");
        }

        @Test
        @DisplayName("LOGIN_REQ without playerId should fail")
        void validate_LoginWithoutPlayerId_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.LOGIN_REQ);
            message.setPlayerId(null);

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getFirstError()).contains("playerId");
        }

        @Test
        @DisplayName("LOGIN_REQ without data should fail")
        void validate_LoginWithoutData_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.LOGIN_REQ);
            message.setData("  ");

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getFirstError()).contains("data");
        }

        @Test
        @DisplayName("PLAY_CARD_REQ with all required fields should pass")
        void validate_PlayCardWithAllFields_ShouldPass() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setPlayerId("player1");
            message.setData("{\"cardId\":\"card1\"}");

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("END_TURN_REQ requires only playerId")
        void validate_EndTurn_OnlyNeedsPlayerId() {
            GameMessage message = GameMessage.builder()
                    .type(MessageType.END_TURN_REQ)
                    .playerId("player1")
                    .timestamp(System.currentTimeMillis())
                    .build();

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("VersionCompatibilityValidator Tests")
    class VersionCompatibilityValidatorTests {

        @Test
        @DisplayName("Message with invalid JSON data should fail")
        void validate_InvalidJsonData_ShouldFail() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData("{invalid json}");

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getFirstError()).contains("JSON");
        }

        @Test
        @DisplayName("Valid JSON data should pass")
        void validate_ValidJsonData_ShouldPass() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData("{\"cardId\":\"card1\",\"targetIds\":[\"enemy1\"]}");

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("Old client version should fail")
        void validate_OldClientVersion_ShouldFail() {
            Map<String, Object> data = new HashMap<>();
            data.put("protocolVersion", 0);

            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData(com.cardgame.common.utils.JsonUtils.toJson(data));

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo(426);
        }

        @Test
        @DisplayName("Current version should pass")
        void validate_CurrentVersion_ShouldPass() {
            Map<String, Object> data = new HashMap<>();
            data.put("protocolVersion", 1);

            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData(com.cardgame.common.utils.JsonUtils.toJson(data));

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("No version should pass (backward compatible)")
        void validate_NoVersion_ShouldPass() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setData("{\"cardId\":\"card1\"}");

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Chain Execution Tests")
    class ChainExecutionTests {

        @Test
        @DisplayName("Validation should stop at first failure")
        void validate_StopAtFirstFailure() {
            GameMessage message = createValidMessage(MessageType.PLAY_CARD_REQ);
            message.setType(null);
            message.setPlayerId("a".repeat(100));

            ValidationResult result = validationChain.validate(message);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getFirstError()).contains("type");
        }

        @Test
        @DisplayName("Validators should execute in order")
        void validate_ExecutesInOrder() {
            LengthValidator lengthValidator = (LengthValidator) validationChain.getValidators().stream()
                    .filter(v -> v instanceof LengthValidator)
                    .findFirst()
                    .orElseThrow();
            RequiredFieldValidator fieldValidator = (RequiredFieldValidator) validationChain.getValidators().stream()
                    .filter(v -> v instanceof RequiredFieldValidator)
                    .findFirst()
                    .orElseThrow();
            VersionCompatibilityValidator versionValidator = (VersionCompatibilityValidator) validationChain.getValidators().stream()
                    .filter(v -> v instanceof VersionCompatibilityValidator)
                    .findFirst()
                    .orElseThrow();

            assertThat(lengthValidator.getOrder()).isLessThan(fieldValidator.getOrder());
            assertThat(fieldValidator.getOrder()).isLessThan(versionValidator.getOrder());
        }

        @Test
        @DisplayName("Custom validator can be added")
        void addValidator_ShouldAddToChain() {
            IMessageValidator customValidator = new IMessageValidator() {
                @Override
                public ValidationResult validate(GameMessage message) {
                    return ValidationResult.success();
                }

                @Override
                public int getOrder() {
                    return 5;
                }
            };

            validationChain.addValidator(customValidator);

            assertThat(validationChain.getValidatorCount()).isEqualTo(4);
            assertThat(validationChain.getValidators().get(0)).isEqualTo(customValidator);
        }
    }

    private GameMessage createValidMessage(MessageType type) {
        return GameMessage.builder()
                .type(type)
                .playerId("player1")
                .roomId("room1")
                .requestId("req123")
                .timestamp(System.currentTimeMillis())
                .data("{\"test\":\"value\"}")
                .build();
    }
}
