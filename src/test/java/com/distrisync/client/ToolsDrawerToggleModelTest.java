package com.distrisync.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast UI-state checks for the collapsible tool palette (no JavaFX toolkit).
 */
class ToolsDrawerToggleModelTest {

    @Test
    void testToolbarToggleState() {
        ToolsDrawerToggleModel model = new ToolsDrawerToggleModel();

        assertTrue(model.isToolsOpen(), "drawer starts open");
        assertEquals("<", model.restChevronText());
        assertEquals(0.0, model.restPanelTranslateX(88.0), 1e-9);

        ToolsDrawerToggleModel.ToggleRestSnapshot after =
                model.simulateToggleAnimationFinished(88.0);

        assertFalse(after.toolsOpen(), "after first toggle, drawer is closed");
        assertEquals(">", after.restChevronText());
        assertTrue(after.restPanelTranslateX() < 0, "settled translate X is off-screen to the left");
        assertTrue(
                after.lastAnimationTargetTranslateX() < 0,
                "close animation targets a negative translate X");
        assertEquals(-88.0, after.restPanelTranslateX(), 1e-9);
        assertEquals(-88.0, after.lastAnimationTargetTranslateX(), 1e-9);

        ToolsDrawerToggleModel.ToggleRestSnapshot reopened = model.simulateToggleAnimationFinished(88.0);
        assertTrue(reopened.toolsOpen(), "second toggle reopens drawer");
        assertEquals("<", reopened.restChevronText());
        assertEquals(0.0, reopened.restPanelTranslateX(), 1e-9);
    }
}
