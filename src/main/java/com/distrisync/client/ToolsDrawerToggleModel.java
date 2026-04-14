package com.distrisync.client;

/**
 * Pure state for the collapsible tools drawer: open/closed flag, chevron labels, and slide target X.
 * JavaFX slide timing stays in {@link WhiteboardApp}; this type
 * is the single source of truth for distances and post-toggle semantics (unit-tested).
 */
public final class ToolsDrawerToggleModel {

    /**
     * Closed-state slide distance equals the tool panel width only. When the whole {@code HBox} row
     * translates, shifting by {@code width + extra} would push the trailing chevron past the left
     * edge and it could no longer receive clicks.
     */
    private static double closedTranslateX(double panelWidth) {
        return -Math.max(panelWidth, 0);
    }

    private boolean toolsOpen = true;

    public boolean isToolsOpen() {
        return toolsOpen;
    }

    /** Chevron shown when the drawer is fully settled (open = collapse affordance, closed = expand). */
    public String restChevronText() {
        return toolsOpen ? "<" : ">";
    }

    /** Drawer row {@code translateX} when fully settled for the current open flag. */
    public double restPanelTranslateX(double panelWidth) {
        return toolsOpen ? 0 : closedTranslateX(panelWidth);
    }

    /**
     * Target {@code translateX} for the in-flight animation from the current settled state
     * (open → negative X, closed → {@code 0}).
     */
    public double animationTargetTranslateX(double panelWidth) {
        return toolsOpen ? closedTranslateX(panelWidth) : 0;
    }

    /** Chevron flipped at animation start (before {@link #commitAfterToggleAnimation()}). */
    public String chevronAtAnimationStart() {
        return toolsOpen ? ">" : "<";
    }

    /** Invoke from the transition {@code onFinished} handler once the slide has completed. */
    public void commitAfterToggleAnimation() {
        toolsOpen = !toolsOpen;
    }

    /**
     * Test helper: one logical user click through to settled state (as if the 250 ms tween finished).
     */
    public ToggleRestSnapshot simulateToggleAnimationFinished(double panelWidth) {
        double targetX = animationTargetTranslateX(panelWidth);
        commitAfterToggleAnimation();
        return new ToggleRestSnapshot(
                isToolsOpen(),
                restChevronText(),
                restPanelTranslateX(panelWidth),
                targetX);
    }

    /** Settled UI snapshot plus the last animation target X (negative when closing). */
    public record ToggleRestSnapshot(
            boolean toolsOpen,
            String restChevronText,
            double restPanelTranslateX,
            double lastAnimationTargetTranslateX) {}
}
