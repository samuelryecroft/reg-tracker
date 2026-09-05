package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.Encrypted;
import org.junit.jupiter.api.Test;

/**
 * T194 - no template may render an {@code @Encrypted} field of {@link Child} directly.
 *
 * <p><b>The defect this exists for (T193).</b> {@code children/list.html} takes its names through
 * the {@link ChildIdentity} projection, so it is a masked surface - and then reaches past the
 * projection to the entity for two other columns, printing every child's exact date of birth and
 * case reference beside the masked name. Both are {@code @Encrypted} on {@code Child}, the same
 * protection class as the names. A masked row carrying initials plus an exact DOB plus a case
 * reference is not masked; it is de-anonymised by its own identity block, on the one screen whose
 * job is choosing which child to act on. Creed's finding, and Kevin's ruling on the shape of it:
 * a mask that prints a DOB <em>makes a false promise</em>, which is worse than not masking.
 *
 * <p><b>Why a guard rather than two edits.</b> {@code ChildIdentity} is a projection precisely so
 * identity rendering is ONE decision. A template that reaches past it to the entity has opted out -
 * <b>and nothing in the codebase said so</b>, which is how this survived a masking design that is
 * otherwise carefully reasoned. The invariant only held where somebody remembered to apply it.
 *
 * <p><b>Why the field list is reflected and never written down.</b> {@link #encryptedFieldsOfChild()}
 * reads {@code Child}'s own {@code @Encrypted} annotations. A hand-maintained list of field names
 * would be true on the day it was written and would then rot silently: the next encrypted field
 * added to {@code Child} would be unguarded, and nothing would say so. Deriving it is the property
 * that makes this worth building rather than fixing the two call sites and moving on - a new
 * {@code @Encrypted} field is covered with no change here.
 *
 * <p><b>Why {@code Child} and not every encrypted entity, which is a real question.</b>
 * {@code InterviewRequest} and {@code InterviewReport} carry about thirty {@code @Encrypted} fields
 * between them, and the report screens exist to render them - that IS the product. Widening this
 * guard to "every {@code @Encrypted} field" would fail on dozens of correct templates. The
 * difference is not that {@code Child}'s data is more sensitive; it is that {@code Child} has a
 * PROJECTION that decides how identity is shown, and the other two do not. <b>The invariant is
 * created by {@code ChildIdentity}, not by the annotation</b>, so the scope follows the projection.
 * If a second projection is ever introduced, this guard should follow it rather than be copied.
 *
 * <p><b>What is allowed, and why the distinction is exact rather than a carve-out.</b> Rendering
 * {@code identity.label()} or {@code identity.avatar()} is the correct path and is untouched here.
 * {@code children/form.html} binds {@code th:object="${form}"} to {@code CreateChildForm}, a DTO -
 * so its {@code *{dateOfBirth}} is a form field, not a read of the entity, and this guard's
 * {@code ${...}} scope excludes it by construction rather than by exception. That is only true
 * while every {@code th:object} in the estate binds a form, which is why
 * {@link #everyFormScopeBindsAFormAndNotAnEntity()} exists: a guard resting on an unstated
 * assumption goes quiet exactly when someone departs from it.
 *
 * <p><b>What this does not cover</b>, stated so its silence is not mistaken for assurance. Template
 * expressions are untyped, so the scan matches on FIELD NAME: if another entity ever gains a
 * PROPERTY named {@code firstName} and a template renders it, this guard will name that line too.
 * There are none today. The near miss that shape actually produced was a projection whose accessors
 * carried Child's own field names - that one is now separated structurally rather than by
 * exception, because an accessor CALL cannot reach a bean's field (see {@link #entityReadOf}). The alternative - an allow-list of variable names known to hold a {@code Child} - is
 * the hand-maintained list this guard was built to avoid, and it would fail in the more dangerous
 * direction. It also cannot see a value that reaches the template already extracted into a model
 * attribute by a controller ({@code model.addAttribute("dob", child.getDateOfBirth())}); that is a
 * Java-side disclosure and belongs to the telemetry/entity guards, not to a template scan.
 */
class EncryptedChildFieldTemplateGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /**
     * Every notation that reads an encrypted field OFF THE ENTITY, and none that merely names it.
     *
     * <p>Three shapes disclose: the bean property read {@code ${c.dateOfBirth}} (Thymeleaf resolves
     * it through {@code getDateOfBirth()}), the explicit getter call {@code ${c.getDateOfBirth()}},
     * and the bracket read {@code ${c['dateOfBirth']}}. <b>The getter form was a live hole in the
     * first version of this guard</b> - it renders identically to the property form, and the T193
     * defect rewritten that way passed silently. It was found the first time somebody used the
     * guard, which is the argument for the synthetic detector test below carrying every notation
     * rather than only the one the original defect happened to use.
     *
     * <p>What is deliberately NOT flagged is {@code .dateOfBirth()} - a bare accessor CALL. That is
     * the shape of a record accessor, and {@code Child} is a bean: {@code c.dateOfBirth()} resolves
     * to no method on it and would fail at render, so it cannot be a disclosure path. Letting it
     * through is not a concession, it is what allows a PROJECTION to keep the honest field name.
     * The first version flagged it, and the first author to build a projection had to rename their
     * accessors to {@code dob()}/{@code caseReference()} to get past this guard - <b>a guard that
     * makes the correct path harder to name is pushing people off the route it exists to
     * enforce.</b> The exemption is checked against {@code Child} rather than assumed: see
     * {@link #accessorStyleMethodNames()}.
     *
     * <p>The leading {@code .} or {@code ['} is what separates a READ from the many legitimate
     * places a field's NAME appears as a string: {@code th:field="*{dateOfBirth}"},
     * {@code #fields.hasErrors('dateOfBirth')}, {@code id="dateOfBirth"},
     * {@code aria-describedby="dateOfBirth-error"}. None of those disclose anything, and a guard
     * that flagged them would be teaching people to add exclusions - which is how a guard stops
     * guarding. The trailing {@code \b} also keeps {@code .dateOfBirthCiphertext} out: that is the
     * persisted ciphertext, not the plaintext face.
     */
    private static Pattern entityReadOf(String field, boolean childHasAccessorStyleMethod) {
        String name = Pattern.quote(field);
        String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        String propertyRead = childHasAccessorStyleMethod
                ? "\\." + name + "\\b"
                : "\\." + name + "\\b(?!\\s*\\()";
        return Pattern.compile(
                propertyRead
                + "|\\." + Pattern.quote(getter) + "\\s*\\("
                + "|(?:\\['|\\[\")" + name + "\\b");
    }

    /**
     * Record-style accessors {@code Child} actually declares - {@code dateOfBirth()} rather than
     * {@code getDateOfBirth()}.
     *
     * <p>Empty today, and the exemption above depends on it staying that way. Reflected rather than
     * assumed because "the entity is a bean, so a bare accessor call cannot reach it" is exactly the
     * kind of convention a guard should not rest on silently: give {@code Child} a
     * {@code dateOfBirth()} method and the exemption would hide a real read. Deriving it means the
     * guard tightens itself the moment that stops being true.
     */
    private static Set<String> accessorStyleMethodNames() {
        Set<String> names = new TreeSet<>();
        for (java.lang.reflect.Method method : Child.class.getMethods()) {
            if (method.getParameterCount() == 0) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static Set<String> encryptedFieldsOfChild() {
        Set<String> names = new TreeSet<>();
        for (Field field : Child.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Encrypted.class)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** Every {@code file:line -> field} in {@code template} that reads an encrypted field. */
    private static List<String> encryptedFieldReads(String name, String template, Set<String> fields) {
        Set<String> accessorStyle = accessorStyleMethodNames();
        List<String> offences = new ArrayList<>();
        String[] lines = template.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String field : fields) {
                if (entityReadOf(field, accessorStyle.contains(field)).matcher(lines[i]).find()) {
                    offences.add(name + ":" + (i + 1) + " renders Child." + field
                            + " -> " + lines[i].trim());
                }
            }
        }
        return offences;
    }

    @Test
    void noTemplateRendersAnEncryptedFieldOfChildDirectly() throws IOException {
        Set<String> fields = encryptedFieldsOfChild();
        assertThat(fields)
                .as("this guard derives its subject matter from Child's own @Encrypted annotations, "
                        + "so finding none means the annotation moved or was renamed and this test "
                        + "is now guarding nothing while still reporting green")
                .isNotEmpty();

        List<Path> templates = templateFiles();
        assertThat(templates)
                .as("a floor on the walk itself: if the template tree moves, this scan would find "
                        + "nothing to check and pass by going quiet")
                .hasSizeGreaterThan(25);

        List<String> offences = new ArrayList<>();
        for (Path template : templates) {
            offences.addAll(encryptedFieldReads(
                    TEMPLATES.relativize(template).toString(),
                    Files.readString(template, StandardCharsets.UTF_8),
                    fields));
        }

        assertThat(offences)
                .as("a template reads an @Encrypted field of Child straight off the entity, going "
                        + "around the ChildIdentity projection that decides how a child is shown. "
                        + "On a masked surface that defeats the mask - initials beside an exact date "
                        + "of birth identify the child anyway, so the page keeps a promise it is "
                        + "visibly breaking. Render identity.label()/identity.avatar(); if you need "
                        + "another field on the page, it goes through the projection or behind the "
                        + "server-side reveal, never straight off the entity")
                .isEmpty();
    }

    /**
     * The assumption the scan above rests on, made explicit.
     *
     * <p>The scan only inspects {@code ${...}} variable expressions. It can afford to ignore
     * {@code *{...}} selection expressions ONLY because every {@code th:object} in this codebase
     * binds a form DTO - so {@code *{dateOfBirth}} on {@code children/form.html} is a field of
     * {@code CreateChildForm}, not a read of {@code Child}. Bind a {@code th:object} to an entity
     * and that stops being true: {@code *{dateOfBirth}} would then render the encrypted field and
     * the guard above would not see it.
     *
     * <p>This is not a style rule about form beans. It is the condition under which the other
     * test's green means what it says, which is why it fails here rather than being a comment.
     */
    @Test
    void everyFormScopeBindsAFormAndNotAnEntity() throws IOException {
        List<String> nonFormScopes = new ArrayList<>();
        for (Path template : templateFiles()) {
            String source = Files.readString(template, StandardCharsets.UTF_8);
            Matcher scope = Pattern.compile("th:object=\"\\$\\{([^}]+)\\}\"").matcher(source);
            while (scope.find()) {
                if (!"form".equals(scope.group(1).trim())) {
                    nonFormScopes.add(TEMPLATES.relativize(template) + " -> " + scope.group(1));
                }
            }
        }

        assertThat(nonFormScopes)
                .as("a th:object bound to something other than the form DTO. The encrypted-field "
                        + "scan reads ${...} expressions only, so anything reachable through *{...} "
                        + "is invisible to it - binding an entity into form scope would put "
                        + "*{dateOfBirth} back on the page with nothing watching. Either bind a form "
                        + "DTO, or widen the scan in this class to selection expressions too")
                .isEmpty();
    }

    /**
     * The detector itself, against text rather than against the estate - so its green means "this
     * finds the bug", not merely "the estate is quiet today". The negative cases are the shapes
     * that legitimately name a field without reading it; each one appeared in a real template while
     * this guard was being written, and a version that flagged them would have been unmergeable.
     */
    @Test
    void theDetectorReadsAValueAndNotEveryMentionOfAFieldName() {
        Set<String> fields = Set.of("dateOfBirth", "localCaseReference");

        assertThat(encryptedFieldReads("list.html",
                "<td th:text=\"${#temporals.format(c.dateOfBirth, 'dd MMM yyyy')}\">DOB</td>", fields))
                .as("the T193 defect exactly as it shipped - the read is nested inside a "
                        + "#temporals call, so anything anchored to ${ alone would miss it")
                .hasSize(1);

        assertThat(encryptedFieldReads("list.html",
                "<td th:text=\"${c.localCaseReference} ?: '-'\">Case reference</td>", fields))
                .as("the second column of the same defect")
                .hasSize(1);

        assertThat(encryptedFieldReads("evasive.html",
                "<td th:text=\"${c['dateOfBirth']}\">DOB</td>", fields))
                .as("bracket syntax is the same read written differently, and a guard that only "
                        + "understood dot notation would be trivially side-stepped")
                .hasSize(1);

        assertThat(encryptedFieldReads("form.html",
                "<input type=\"date\" id=\"dateOfBirth\" th:field=\"*{dateOfBirth}\" required\n"
                        + "       th:aria-invalid=\"${#fields.hasErrors('dateOfBirth')}\" "
                        + "aria-describedby=\"dateOfBirth-error\"/>", fields))
                .as("the create/edit form names the field four times and reads the entity none of "
                        + "them: *{} is form-DTO scope, and the rest are the field's NAME as a string")
                .isEmpty();

        assertThat(encryptedFieldReads("errors.html",
                "<a href=\"#dateOfBirth\" th:text=\"${#fields.errors('dateOfBirth')[0]}\">Error</a>", fields))
                .as("a validation message names the field it is about; it discloses nothing")
                .isEmpty();

        assertThat(encryptedFieldReads("list.html",
                "<td th:text=\"${c.dateOfBirthCiphertext}\">?</td>", fields))
                .as("the ciphertext sibling is not the plaintext face this guard protects, and "
                        + "matching it would be a false positive on a different field entirely")
                .isEmpty();

        assertThat(encryptedFieldReads("getter.html",
                "<td th:text=\"${c.getDateOfBirth()}\">DOB</td>", fields))
                .as("the explicit getter renders identically to the property form and was a live "
                        + "hole in the first version of this guard - the T193 defect written this "
                        + "way passed silently, so its green was a false assurance")
                .hasSize(1);

        assertThat(encryptedFieldReads("projection.html",
                "<td th:text=\"${childRows[c.id].dateOfBirth()}\">DOB</td>", fields))
                .as("a bare accessor CALL is a record accessor - Child is a bean and has no such "
                        + "method, so this cannot reach the entity. Flagging it forced the first "
                        + "author of a projection to rename their accessors, which is the guard "
                        + "pushing people off the route it exists to enforce")
                .isEmpty();

        assertThat(encryptedFieldReads("masked.html",
                "<td th:text=\"${childIdentities[c.id].label()}\">Name</td>", fields))
                .as("the correct path - the projection - must stay green, or the guard would be "
                        + "pushing people off the very route it exists to enforce")
                .isEmpty();
    }

    private static List<Path> templateFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .sorted()
                    .toList();
        }
    }
}
