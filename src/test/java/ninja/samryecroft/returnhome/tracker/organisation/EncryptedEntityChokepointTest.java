package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T168(b), and the reason the activation guard is allowed to sit in one place.
 *
 * <p>The guard gates CHILD creation only. Three entities carry encrypted fields, so that looks
 * partial - and the argument that it isn't rests on a structural fact rather than on a survey: an
 * {@code InterviewRequest} requires a {@code Child}, and an {@code InterviewReport} hangs off a
 * request, so for an organisation with no confirmed KEK the child record is the only door into the
 * encrypted class.
 *
 * <p><b>That argument is true today and nothing makes it stay true.</b> A fourth
 * {@code EncryptedEntity} - notes on a home, a contact record, anything reachable without a child -
 * would silently reopen the path, and every other test in the suite would keep passing, because
 * nothing else asserts anything about the SET of encrypted entities. This test fails the day that
 * happens, which is what converts "sound by dependency" into "will tell us when it stops being
 * sound". Kevin's framing: the argument is fine, the test is what makes being clever safe.
 *
 * <p>It is a source scan because the property is about which types exist, not about what any of
 * them does at runtime. If it fails, the question to answer is not "how do I make this pass" but
 * "can this new entity be created for an organisation that has no KEK, without a child existing
 * first?" - if it can, it needs the guard too.
 */
class EncryptedEntityChokepointTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /**
     * The entities the child-creation gate is known to cover, and why each is covered:
     * <ul>
     *   <li>{@code Child} - gated directly, in {@code ChildController.create}.</li>
     *   <li>{@code InterviewRequest} - cannot exist without a Child.</li>
     *   <li>{@code InterviewReport} - cannot exist without an InterviewRequest.</li>
     * </ul>
     */
    private static final List<String> COVERED_BY_THE_CHILD_GATE =
            List.of("Child", "InterviewRequest", "InterviewReport");

    @Test
    void everyEncryptedEntityIsStillReachableOnlyThroughAGatedChildRecord() throws IOException {
        List<String> encryptedEntities = encryptedEntityNames();

        assertThat(encryptedEntities)
                .as("a new @EncryptedEntity has appeared. The T168(b) activation guard gates CHILD "
                        + "creation only, which is safe precisely because every other encrypted "
                        + "entity depends on a Child existing first. Before adding it to this list, "
                        + "answer: can this be created for an organisation with no confirmed KEK, "
                        + "without a child? If it can, it needs the guard too - not an entry here")
                .containsExactlyInAnyOrderElementsOf(COVERED_BY_THE_CHILD_GATE);
    }

    private static List<String> encryptedEntityNames() throws IOException {
        assertThat(MAIN_JAVA).as("the scanned source tree must exist - a moved tree must not "
                + "silently turn this guard into a no-op").exists();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(EncryptedEntityChokepointTest::implementsEncryptedEntity)
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .toList();
        }
    }

    /** The declaration, not a mention: an import or a javadoc reference must not count. */
    private static boolean implementsEncryptedEntity(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> line.contains("class ") && line.contains("implements")
                            && line.contains("EncryptedEntity"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }
}
