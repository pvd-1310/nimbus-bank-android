package com.example.final_term.ui.transaction;

import org.junit.Test;

import static org.junit.Assert.*;

public class OtpDigitControllerTest {
    @Test
    public void appendBackspaceAndClampToSixDigits() {
        OtpDigitController controller = new OtpDigitController();
        assertEquals(0, controller.getActiveIndex());
        assertFalse(controller.isComplete());

        for (char c : new char[]{'5', '9', '2', '4', '0', '1', '8'}) {
            controller.appendDigit(c);
        }
        assertEquals("592401", controller.getCode());
        assertEquals(6, controller.getLength());
        assertTrue(controller.isComplete());
        assertEquals(-1, controller.getActiveIndex());

        controller.backspace();
        assertEquals("59240", controller.getCode());
        assertEquals(5, controller.getLength());
        assertEquals(5, controller.getActiveIndex());
        assertFalse(controller.isComplete());
    }

    @Test
    public void setCodeAutoFillsValidSixDigitsAndIgnoresInvalidInput() {
        OtpDigitController controller = new OtpDigitController();
        controller.setCode("123456");
        assertEquals("123456", controller.getCode());
        assertTrue(controller.isComplete());
        assertEquals('6', controller.digitAt(5));

        controller.appendDigit('x'); // non-digit ignored
        assertEquals("123456", controller.getCode());

        controller.clear();
        assertEquals("", controller.getCode());
        assertEquals(0, controller.getActiveIndex());
    }

    @Test
    public void handlesNullMixedCodeOutOfRangeDigitAtAndEmptyBackspace() {
        OtpDigitController controller = new OtpDigitController();
        controller.backspace(); // no-op when empty
        assertEquals(0, controller.getLength());
        assertEquals('\0', controller.digitAt(-1));
        assertEquals('\0', controller.digitAt(0));
        assertEquals('\0', controller.digitAt(6));

        controller.setCode("1a2b34567");
        assertEquals("123456", controller.getCode());
        assertEquals('1', controller.digitAt(0));
        assertEquals('6', controller.digitAt(5));

        controller.setCode(null);
        assertEquals("", controller.getCode());
        assertEquals(0, controller.getActiveIndex());
    }
}
