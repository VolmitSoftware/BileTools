package com.volmit.bile.config;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BileConfigEditorContractTest {
    @Test
    public void centersCategorySettingsWithinTheEditorGrid() {
        assertEquals(List.of(22), BileConfigEditor.settingSlots(1));
        assertEquals(List.of(21, 22), BileConfigEditor.settingSlots(2));
        assertEquals(List.of(21, 22, 23), BileConfigEditor.settingSlots(3));
        assertEquals(List.of(20, 21, 22, 23, 24), BileConfigEditor.settingSlots(5));
        assertEquals(2, BileConfigEditor.settingSlots(5).indexOf(22));
        assertEquals(-1, BileConfigEditor.settingSlots(5).indexOf(0));
        for (int slot : BileConfigEditor.settingSlots(35)) {
            assertTrue(slot >= 0 && slot < 45);
        }
    }
}
