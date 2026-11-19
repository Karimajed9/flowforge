package com.karimmajed.flowforge.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionEvaluatorTest {

    @Test
    void comparesEqualityAndNumericBoundariesWithoutCoercion() {
        Map<String, Object> equals = condition("EQUALS", 1);
        assertThat(ConditionEvaluator.taken(equals, Map.of("amount", 1.0))).isTrue();
        assertThat(ConditionEvaluator.taken(equals, Map.of("amount", 2))).isFalse();

        Map<String, Object> greater = condition("GREATER_THAN", 10);
        assertThat(ConditionEvaluator.taken(greater, Map.of("amount", 10))).isFalse();
        assertThat(ConditionEvaluator.taken(greater, Map.of("amount", 11))).isTrue();
        assertThat(ConditionEvaluator.taken(greater, Map.of("amount", 9))).isFalse();

        Map<String, Object> less = condition("LESS_THAN", 10);
        assertThat(ConditionEvaluator.taken(less, Map.of("amount", 10))).isFalse();
        assertThat(ConditionEvaluator.taken(less, Map.of("amount", 9))).isTrue();

        Map<String, Object> different = condition("NOT_EQUALS", "paid");
        assertThat(ConditionEvaluator.taken(different, Map.of("amount", "open"))).isTrue();
        assertThat(ConditionEvaluator.taken(different, Map.of("amount", "paid"))).isFalse();

        Map<String, Object> nullEquals = condition("EQUALS", null);
        Map<String, Object> presentNull = new HashMap<>();
        presentNull.put("amount", null);
        assertThat(ConditionEvaluator.taken(nullEquals, presentNull)).isTrue();
    }

    @Test
    void missingFieldsAndIncompatibleTypesFailTheComparison() {
        Map<String, Object> equals = condition("EQUALS", 10);
        assertThatThrownBy(() -> ConditionEvaluator.taken(equals, Map.of()))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition field is missing: amount");
        assertThatThrownBy(() -> ConditionEvaluator.taken(equals, Map.of("amount", "10")))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': expected NUMBER, but received STRING.");
        assertThatThrownBy(() -> ConditionEvaluator.taken(
                condition("NOT_EQUALS", "10"),
                Map.of("amount", 10)))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': expected STRING, but received NUMBER.");
        assertThatThrownBy(() -> ConditionEvaluator.taken(
                condition("EQUALS", true),
                Map.of("amount", 1)))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': expected BOOLEAN, but received NUMBER.");

        Map<String, Object> presentNull = new HashMap<>();
        presentNull.put("amount", null);
        assertThatThrownBy(() -> ConditionEvaluator.taken(equals, presentNull))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': expected NUMBER, but received NULL.");
        assertThatThrownBy(() -> ConditionEvaluator.taken(
                condition("EQUALS", null),
                Map.of("amount", "open")))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': expected NULL, but received STRING.");
    }

    @Test
    void numericOperatorsRejectNonNumbersAndObjectsAreUnsupported() {
        Map<String, Object> greater = condition("GREATER_THAN", 10);
        assertThatThrownBy(() -> ConditionEvaluator.taken(greater, Map.of("amount", "11")))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage(
                        "Condition 'amount': operator GREATER_THAN requires numbers (expected NUMBER, received STRING).");
        assertThatThrownBy(() -> ConditionEvaluator.taken(greater, Map.of("amount", false)))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage(
                        "Condition 'amount': operator GREATER_THAN requires numbers (expected NUMBER, received BOOLEAN).");

        Map<String, Object> presentNull = new HashMap<>();
        presentNull.put("amount", null);
        assertThatThrownBy(() -> ConditionEvaluator.taken(
                condition("LESS_THAN", 10),
                presentNull))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage(
                        "Condition 'amount': operator LESS_THAN requires numbers (expected NUMBER, received NULL).");

        assertThatThrownBy(() -> ConditionEvaluator.taken(greater, Map.of("amount", List.of(11))))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': unsupported type (expected NUMBER, received OTHER).");
        assertThatThrownBy(() -> ConditionEvaluator.taken(
                greater,
                Map.of("amount", Map.of("nested", 11))))
                .isInstanceOf(ConditionEvaluator.InvalidCondition.class)
                .hasMessage("Condition 'amount': unsupported type (expected NUMBER, received OTHER).");
    }

    @Test
    void existsTreatsNullAsPresentAndAMissingKeyAsFalse() {
        Map<String, Object> exists = new HashMap<>();
        exists.put("field", "amount");
        exists.put("operator", "EXISTS");
        exists.put("trueSteps", List.of(2L));
        exists.put("falseSteps", List.of(3L));

        Map<String, Object> presentNull = new HashMap<>();
        presentNull.put("amount", null);
        assertThat(ConditionEvaluator.taken(exists, presentNull)).isTrue();
        assertThat(ConditionEvaluator.taken(exists, Map.of("other", 1))).isFalse();
        assertThat(ConditionEvaluator.skippedIds(exists, false)).containsExactly(2L);
        assertThat(ConditionEvaluator.skippedIds(exists, true)).containsExactly(3L);
    }

    private static Map<String, Object> condition(String operator, Object value) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("field", "amount");
        configuration.put("operator", operator);
        configuration.put("value", value);
        configuration.put("trueSteps", List.of(2L));
        configuration.put("falseSteps", List.of(3L));
        return configuration;
    }
}
