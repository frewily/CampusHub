package io.github.frewily.campushub.identity.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HutoolLoginCodeGeneratorTest {
    @Test void generatesSixDigits() {
        String code = new HutoolLoginCodeGenerator().generate();
        assertTrue(code.matches("\\d{6}"));
    }
}
