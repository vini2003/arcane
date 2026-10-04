package dev.omega.arcane.precedence;

import dev.omega.arcane.Molang;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PEMDASTests {

    @Test
    public void PEMDAS_MultiplicationAddition_Success() {
        // 5.0 + 5.0 * 5.0 => 5.0 + (5.0 * 5.0) => 5.0 + (25.0) => 30.0
        Assertions.assertEquals(30.0, Molang.evaluateUnchecked("5.0 + 5.0 * 5.0"));
    }

    @Test
    public void PEMDAS_SubtractionThenAddition_LeftToRight() {
        // 1 - 2 + 3 => (1 - 2) + 3 => 2; the "+ 3" used to be dropped.
        Assertions.assertEquals(2.0, Molang.evaluateUnchecked("1 - 2 + 3"));
        Assertions.assertEquals(5.0, Molang.evaluateUnchecked("10 - 2 - 3"));
        Assertions.assertEquals(4.0, Molang.evaluateUnchecked("1 + 2 - 3 + 4"));
    }

    @Test
    public void PEMDAS_DivisionThenMultiplication_LeftToRight() {
        // 8 / 2 * 3 => (8 / 2) * 3 => 12; the "* 3" used to be dropped.
        Assertions.assertEquals(12.0, Molang.evaluateUnchecked("8 / 2 * 3"));
        Assertions.assertEquals(1.0, Molang.evaluateUnchecked("8 / 2 / 4"));
    }
}
