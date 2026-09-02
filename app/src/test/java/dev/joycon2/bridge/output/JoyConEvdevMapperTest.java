package dev.joycon2.bridge.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class JoyConEvdevMapperTest {
    @Test
    public void combinedMapsPairToOneCompleteGamepad() {
        JoyConEvdevMapper mapper = configuredMapper();
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_UP, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_RIGHT, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_TL, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_TL2, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_SELECT, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_EAST, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_TR, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_TR2, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_START, true);

        MappedGamepad state = mapper.combined();

        assertEquals(1, state.hat());
        assertTrue((state.buttons() & MappedGamepad.A) != 0);
        assertTrue((state.buttons() & MappedGamepad.L1) != 0);
        assertTrue((state.buttons() & MappedGamepad.R1) != 0);
        assertTrue((state.buttons() & MappedGamepad.L2) != 0);
        assertTrue((state.buttons() & MappedGamepad.R2) != 0);
        assertTrue((state.buttons() & MappedGamepad.SELECT) != 0);
        assertTrue((state.buttons() & MappedGamepad.START) != 0);
        assertEquals(1f, state.leftTrigger(), 0.0001f);
        assertEquals(1f, state.rightTrigger(), 0.0001f);
    }

    @Test
    public void leftSidewaysRotatesStickAndDpadByPosition() {
        JoyConEvdevMapper mapper = configuredMapper();
        mapper.setAxis(
                JoyConEvdevMapper.SIDE_LEFT,
                JoyConEvdevMapper.ABS_X,
                32767,
                -32767,
                32767,
                500
        );
        mapper.setAxis(
                JoyConEvdevMapper.SIDE_LEFT,
                JoyConEvdevMapper.ABS_Y,
                -32767,
                -32767,
                32767,
                500
        );
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_DOWN, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_LEFT, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_RIGHT, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_UP, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_TR, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_TR2, true);

        MappedGamepad state = mapper.sidewaysLeft();

        assertEquals(-1f, state.leftX(), 0.0001f);
        assertEquals(-1f, state.leftY(), 0.0001f);
        assertTrue((state.buttons() & MappedGamepad.A) != 0);
        assertTrue((state.buttons() & MappedGamepad.B) != 0);
        assertTrue((state.buttons() & MappedGamepad.X) != 0);
        assertTrue((state.buttons() & MappedGamepad.Y) != 0);
        assertTrue((state.buttons() & MappedGamepad.L1) != 0);
        assertTrue((state.buttons() & MappedGamepad.R1) != 0);
    }

    @Test
    public void combinedSwapsOnlyRequestedFaceButtonPairs() {
        JoyConEvdevMapper mapper = configuredMapper();

        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_EAST, true);
        MappedGamepad physicalA = mapper.combined(true, false);
        assertFalse((physicalA.buttons() & MappedGamepad.A) != 0);
        assertTrue((physicalA.buttons() & MappedGamepad.B) != 0);

        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_EAST, false);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_SOUTH, true);
        MappedGamepad physicalB = mapper.combined(true, false);
        assertTrue((physicalB.buttons() & MappedGamepad.A) != 0);
        assertFalse((physicalB.buttons() & MappedGamepad.B) != 0);

        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_SOUTH, false);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_NORTH, true);
        MappedGamepad physicalX = mapper.combined(false, true);
        assertFalse((physicalX.buttons() & MappedGamepad.X) != 0);
        assertTrue((physicalX.buttons() & MappedGamepad.Y) != 0);

        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_NORTH, false);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_WEST, true);
        MappedGamepad physicalY = mapper.combined(false, true);
        assertTrue((physicalY.buttons() & MappedGamepad.X) != 0);
        assertFalse((physicalY.buttons() & MappedGamepad.Y) != 0);
    }

    @Test
    public void rightSidewaysRotatesStickAndAbxyByPosition() {
        JoyConEvdevMapper mapper = configuredMapper();
        mapper.setAxis(
                JoyConEvdevMapper.SIDE_RIGHT,
                JoyConEvdevMapper.ABS_RX,
                32767,
                -32767,
                32767,
                500
        );
        mapper.setAxis(
                JoyConEvdevMapper.SIDE_RIGHT,
                JoyConEvdevMapper.ABS_RY,
                -32767,
                -32767,
                32767,
                500
        );
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_NORTH, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_EAST, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_WEST, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_SOUTH, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_TL, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_TL2, true);

        MappedGamepad state = mapper.sidewaysRight();

        assertEquals(1f, state.leftX(), 0.0001f);
        assertEquals(1f, state.leftY(), 0.0001f);
        assertTrue((state.buttons() & MappedGamepad.A) != 0);
        assertTrue((state.buttons() & MappedGamepad.B) != 0);
        assertTrue((state.buttons() & MappedGamepad.X) != 0);
        assertTrue((state.buttons() & MappedGamepad.Y) != 0);
        assertTrue((state.buttons() & MappedGamepad.L1) != 0);
        assertTrue((state.buttons() & MappedGamepad.R1) != 0);
    }

    @Test
    public void leftSidewaysSupportsIndependentAbAndXySwaps() {
        JoyConEvdevMapper mapper = configuredMapper();
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_DOWN, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.BTN_DPAD_RIGHT, true);

        MappedGamepad state = mapper.sidewaysLeft(true, true);

        assertFalse((state.buttons() & MappedGamepad.A) != 0);
        assertTrue((state.buttons() & MappedGamepad.B) != 0);
        assertFalse((state.buttons() & MappedGamepad.X) != 0);
        assertTrue((state.buttons() & MappedGamepad.Y) != 0);
    }

    @Test
    public void rightSidewaysSupportsIndependentAbAndXySwaps() {
        JoyConEvdevMapper mapper = configuredMapper();
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_NORTH, true);
        mapper.setKey(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.BTN_WEST, true);

        MappedGamepad state = mapper.sidewaysRight(true, true);

        assertFalse((state.buttons() & MappedGamepad.A) != 0);
        assertTrue((state.buttons() & MappedGamepad.B) != 0);
        assertFalse((state.buttons() & MappedGamepad.X) != 0);
        assertTrue((state.buttons() & MappedGamepad.Y) != 0);
    }

    @Test
    public void mappedReportUsesAndroidGenericButtonSlots() {
        MappedGamepad state = new MappedGamepad(
                MappedGamepad.A | MappedGamepad.X | MappedGamepad.L1 | MappedGamepad.START,
                8,
                0f,
                0f,
                0f,
                0f,
                0f,
                0f
        );

        byte[] report = HidGamepadReport.encode(state);

        assertEquals(1, Byte.toUnsignedInt(report[0]));
        assertEquals(0x49, Byte.toUnsignedInt(report[1]));
        assertEquals(0x08, Byte.toUnsignedInt(report[2]));
        assertEquals(8, Byte.toUnsignedInt(report[3]));
    }

    private static JoyConEvdevMapper configuredMapper() {
        JoyConEvdevMapper mapper = new JoyConEvdevMapper();
        mapper.setAxis(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.ABS_X,
                0, -32767, 32767, 500);
        mapper.setAxis(JoyConEvdevMapper.SIDE_LEFT, JoyConEvdevMapper.ABS_Y,
                0, -32767, 32767, 500);
        mapper.setAxis(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.ABS_RX,
                0, -32767, 32767, 500);
        mapper.setAxis(JoyConEvdevMapper.SIDE_RIGHT, JoyConEvdevMapper.ABS_RY,
                0, -32767, 32767, 500);
        return mapper;
    }
}
