package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T168(b), the second axis - and the one that actually matters for where the guard lives.
 *
 * <p>{@link EncryptedEntityChokepointTest} guards the set of encrypted entity TYPES. Kevin's review
 * pointed out that this leaves the real risk uncovered: the activation guard sits in
 * {@code ChildController}, so the thing that silently bypasses it is not a fourth
 * {@code EncryptedEntity} but <b>a second way to create a Child</b> - a bulk import, an API
 * endpoint, another controller. That is a far more likely change, and every other test in the suite
 * would keep passing through it.
 *
 * <p>So this guards paths rather than types. Two axes, two guards, and between them the argument for
 * one gate is closed: a new encrypted entity is caught by the other test, a new door to the existing
 * one is caught here.
 *
 * <p>The controller placement it protects is deliberate, not convenient. There is no
 * {@code ChildService} in this codebase, so "enforce it deeper" would mean inventing a service layer
 * to host a single rule; and the guard's whole value is refusing EARLY with something the person can
 * act on, which a field error on the form they are looking at delivers and an exception three layers
 * down does not.
 */
class ChildCreationPathGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /**
     * Where a Child may be persisted, and what makes each safe:
     * <ul>
     *   <li>{@code ChildController} - carries the activation guard itself.</li>
     *   <li>{@code DemoDataSeeder} - activates its organisations before creating children, through
     *       {@code OrganisationLifecycleService}, so it cannot seed into a PENDING organisation.</li>
     * </ul>
     */
    private static final List<String> PERMITTED = List.of("ChildController", "DemoDataSeeder");

    @Test
    void everyPathThatPersistsAChildIsOneThatChecksTheOrganisationIsActive() throws IOException {
        List<String> writers = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.contains("childRepository.save(") || source.contains("childRepository.saveAll(")) {
                writers.add(file.getFileName().toString().replace(".java", ""));
            }
        }

        assertThat(writers)
                .as("a new path persists a Child. The T168(b) activation guard lives in "
                        + "ChildController, so any OTHER way of creating a child bypasses it "
                        + "silently and nothing else in the suite notices. Before adding it here, "
                        + "answer: does this path check the organisation is ACTIVE before writing? "
                        + "If it does not, it needs to - an entry in this list is not the fix")
                .containsExactlyInAnyOrderElementsOf(PERMITTED);
    }

    private static List<Path> javaSources() throws IOException {
        assertThat(MAIN_JAVA).as("the scanned source tree must exist - a moved tree must not "
                + "silently turn this guard into a no-op").exists();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }
}
