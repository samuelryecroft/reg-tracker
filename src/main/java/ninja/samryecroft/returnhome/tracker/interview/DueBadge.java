package ninja.samryecroft.returnhome.tracker.interview;

/**
 * A rendered due-state badge: the {@code .due.*} CSS modifier from the design system plus a
 * glyph-and-duration label. Due state is never colour alone (WCAG 1.4.1) - {@code text} always
 * carries the same information the colour does.
 */
public record DueBadge(DueState state, String cssClass, String text) {
}
