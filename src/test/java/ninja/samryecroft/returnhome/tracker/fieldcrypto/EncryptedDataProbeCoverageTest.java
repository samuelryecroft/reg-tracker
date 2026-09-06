package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every encrypted entity is reachable by the probe that decides whether an organisation already
 * holds encrypted data.
 *
 * <p><b>Why the probe needs a hand-written list at all.</b> Each encrypted entity can name its
 * owning organisation in Java - that is what {@link EncryptedEntity} is for - but each reaches it by
 * a different association, and a query needs the PATH. No interface can supply that, so the mapping
 * is explicit.
 *
 * <p><b>And an explicit list is exactly the thing that falls behind.</b> Add a fourth encrypted
 * entity, forget this file, and the probe answers "no encrypted data" for an organisation that has
 * some - which is the fail-OPEN direction: the guard would wave through the mint it exists to
 * refuse, and say nothing. This test is what makes the omission a red build instead.
 *
 * <p>The entity list comes from the JPA metamodel rather than from a second list here, because a
 * hand-maintained copy of "which entities are encrypted" would have the same defect one level up.
 */
@SpringBootTest
class EncryptedDataProbeCoverageTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void everyEncryptedEntityHasAPathToItsOrganisation() {
        List<String> encrypted = entityManager.getMetamodel().getEntities().stream()
                .filter(type -> EncryptedEntity.class.isAssignableFrom(type.getJavaType()))
                .map(type -> type.getJavaType().getSimpleName())
                .sorted()
                .toList();

        assertThat(encrypted)
                .as("the metamodel found no encrypted entities at all, so the comparison below "
                        + "would pass while checking nothing")
                .isNotEmpty();

        assertThat(EncryptedDataProbe.coveredEntityNames())
                .as("an encrypted entity the probe cannot see makes it answer 'no encrypted data' "
                        + "for an organisation that has some - the FAIL-OPEN direction, in which "
                        + "the guard waves through the very mint it exists to refuse and says "
                        + "nothing about it")
                .containsExactlyInAnyOrderElementsOf(encrypted);
    }
}
