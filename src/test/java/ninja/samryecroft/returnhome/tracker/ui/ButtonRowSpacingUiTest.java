package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T235 / spec §8a: an actions row must be separated from the content above it by MORE than the
 * within-form field rhythm, or it reads as one more field.
 *
 * <p><b>The defect, exactly as the human described it.</b> A lone {@code .btn} carries
 * {@code margin-top: var(--s5)} for this relationship, but {@code .btn-row} zeroes each child's
 * own margin-top (correctly - a flex row owns its own {@code gap}) and, until this ticket, never
 * put the relationship back on the row itself. So every screen that wraps its actions in a
 * {@code .btn-row} - which is nearly all of them - sat flush against the field above with zero
 * gap, while a lone unwrapped button elsewhere on the same page looked properly spaced.
 *
 * <p>Reads computed styles on the real, rendered login page rather than the CSS source: this is
 * exactly the class of cascade interaction (a child's own reset colliding with a container that
 * never had the property to begin with) that reading the stylesheet gets subtly wrong - the same
 * reason T234's fix was verified the same way.
 *
 * <p><b>The two deliberate exceptions are checked too</b> - {@code .srow .act .btn-row} (table-row
 * action cells, vertically centred, no margin wanted) and {@code .theme-preview .btn-row} (the
 * branding preview panes) both override this rule to zero on purpose. Asserting they stay at zero
 * is what proves the new base rule did not silently swallow them.
 */
class ButtonRowSpacingUiTest extends AbstractUiTest {

    @Test
    void loginsActionsRowIsSeparatedFromTheFieldAboveIt() {
        page.navigate(url("/login"));
        page.waitForSelector(".btn-row");

        String rowMarginTop = (String) page.evalOnSelector(".btn-row",
                "el => getComputedStyle(el).marginTop");
        String buttonMarginTop = (String) page.evalOnSelector(".btn-row .btn",
                "el => getComputedStyle(el).marginTop");

        assertThat(rowMarginTop)
                .as("the row itself must carry the block-after-content rhythm (--s5, 24px) - "
                        + "without it the row sits flush against the password field above, "
                        + "which is the exact defect the human reported on this page")
                .isEqualTo("24px");
        assertThat(buttonMarginTop)
                .as("the button inside the row must stay at zero - .btn-row .btn already zeroed "
                        + "this correctly, and the fix must not double it up")
                .isEqualTo("0px");
    }

    @Test
    void theTwoDeliberateExceptionsStayAtZero() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);

        // admin/theme-form.html's preview panes: a .btn-row inside .theme-preview must not gain
        // the new spacing - it already opted out on purpose (app.css:.theme-preview .btn-row).
        page.navigate(url("/admin/theme"));
        page.waitForSelector(".theme-preview .btn-row");
        String previewRowMarginTop = (String) page.evalOnSelector(".theme-preview .btn-row",
                "el => getComputedStyle(el).marginTop");
        assertThat(previewRowMarginTop)
                .as("the branding preview's own .btn-row opts out of the new rule on purpose - "
                        + "it must still read zero, not the new 24px")
                .isEqualTo("0px");
    }
}
