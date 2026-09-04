package ninja.samryecroft.returnhome.tracker.interview;

/**
 * A rendered due-state badge: the {@code .due.*} CSS modifier from the design system plus a label.
 *
 * <p>Due state is never colour alone (WCAG 1.4.1) - and, since T165, never a glyph alone either.
 * {@code text} is announced verbatim, so it carries the state as a word ({@link DueStateCopy});
 * {@code state} is what the {@code dueIcon} fragment reads to pick the matching aria-hidden icon.
 * A presentation glyph inside {@code text} would be read out as the character's name.
 */
public record DueBadge(DueState state, String cssClass, String text) {
}
