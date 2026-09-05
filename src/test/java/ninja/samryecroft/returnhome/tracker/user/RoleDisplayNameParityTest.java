package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T119 4d: the role labels in {@code role-constraints.js} say the same words as
 * {@link Role#getDisplayName()}.
 *
 * <p><b>Why the duplication exists at all.</b> {@code role-constraints.js} is served as a static
 * asset, not rendered through Thymeleaf, so it cannot read the enum. The chips on 4d and the
 * editor's aria-live constraint note sit inches apart on the same screen and are produced by the
 * two different sources, so the only alternatives were to duplicate the labels or to leave the note
 * speaking in {@code HOME_STAFF} while the chips beside it say "Home Staff".
 *
 * <p><b>Why a test rather than a comment asking people to keep them in step.</b> The duplication
 * <em>fails silently</em>: rename a label on the enum and every chip changes, the note does not,
 * and nothing breaks. It is the same shape as the break-glass alert marker duplicated between Java
 * and Terraform, and it gets the same treatment - the two sides are asserted equal here so they
 * cannot drift without a red build.
 *
 * <p><b>Both directions matter.</b> A missing entry is the drift that shows (the note falls back to
 * the raw value); an entry for a role that no longer exists is the drift that does not, so the map
 * is checked for being exactly the enum, not merely a superset of it.
 */
class RoleDisplayNameParityTest {

    private static final Path SCRIPT = Path.of("src/main/resources/static/js/role-constraints.js");

    /** The object literal assigned to {@code LABELS}, and only that one. */
    private static final Pattern LABELS_BLOCK =
            Pattern.compile("var\\s+LABELS\\s*=\\s*\\{(.*?)\\}\\s*;", Pattern.DOTALL);

    private static final Pattern ENTRY =
            Pattern.compile("(\\w+)\\s*:\\s*'([^']*)'");

    @Test
    void everyRoleLabelInTheScriptMatchesTheEnum() throws IOException {
        Map<String, String> fromScript = labelsFromScript();

        assertThat(fromScript)
                .as("the LABELS map must be found and parsed - an empty result means the literal "
                        + "moved or changed shape and this guard is checking nothing")
                .isNotEmpty();

        Map<String, String> fromEnum = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            fromEnum.put(role.name(), role.getDisplayName());
        }

        assertThat(fromScript)
                .as("role-constraints.js labels the roles for its aria-live constraint note; the 4d "
                        + "chips beside that note are labelled from Role.getDisplayName(). Rename "
                        + "one and the other does not follow, and nothing else notices - the note "
                        + "just starts speaking a different vocabulary from the screen it explains")
                .containsExactlyInAnyOrderEntriesOf(fromEnum);
    }

    private static Map<String, String> labelsFromScript() throws IOException {
        String js = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        Matcher block = LABELS_BLOCK.matcher(js);
        Map<String, String> labels = new LinkedHashMap<>();
        if (!block.find()) {
            return labels;
        }
        Matcher entry = ENTRY.matcher(block.group(1));
        while (entry.find()) {
            labels.put(entry.group(1), entry.group(2));
        }
        return labels;
    }
}
