package ninja.samryecroft.returnhome.tracker;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * T387 (CODE-REVIEW-2026-09-18 §5): the first architecture rules this codebase has had. Until now
 * nothing would notice a new package cycle or a new controller reaching straight past the services
 * into a repository - "a repository call in a controller is one line away from an unscoped query",
 * and organisation scoping is applied by hand at every call site (T117, T130, T136 were the same
 * defect three times).
 *
 * <p>Two rules, both true today. The cycle rule the review asks for is NOT here: the real import
 * graph has one strongly connected component of 13 packages, so a cycle rule would either fail
 * outright or need a frozen store recording hundreds of edges. It becomes possible after T388 (RLS)
 * and T389 (the audit inversion) break the two biggest cuts, and the {@code core} rule below is
 * the first stone of that: a package nothing may be pulled back into.
 */
class ArchitectureGuardTest {

    private static final String ROOT = "ninja.samryecroft.returnhome.tracker";

    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    /**
     * THE RATCHET. Every controller that reaches a repository directly today, by name. A controller
     * not on this list that imports a repository fails the build; a controller on this list that
     * stops importing one ALSO fails, with a message saying to remove it, so the list can only get
     * shorter. T388 (organisation scoping by construction) works this list down; the target is
     * empty.
     */
    private static final Set<String> CONTROLLERS_STILL_HOLDING_A_REPOSITORY = Set.of(
            "AuditFeedController",
            "CaseFileExportPageController",
            "ChildController",
            "CoordinatorController",
            "ExportController",
            "HomeAdminController",
            "HomeStaffRequestController",
            "OrganisationAdminController",
            "SecondFactorController",
            "UserAdminController");

    @Test
    void coreDependsOnNothingElseInTheApplication() {
        noClasses().that().resideInAPackage(ROOT + ".core..")
                .should().dependOnClassesThat(
                        resideInAPackage(ROOT + "..").and(not(resideInAPackage(ROOT + ".core.."))))
                .because("core is the vocabulary every package may use precisely because it pulls "
                        + "none of them in (T378 seeded it; CODE-REVIEW-2026-09-18 §5 grows it)")
                .check(APPLICATION);
    }

    @Test
    void noNewControllerReachesARepositoryDirectly() {
        classes().that().haveSimpleNameEndingWith("Controller").and().resideInAPackage(ROOT + "..")
                .should(holdARepositoryOnlyIfAlreadyListed())
                .check(APPLICATION);
    }

    /** The list is a fact about the code, so a stale entry is as much a failure as a new offender. */
    @Test
    void everyListedControllerExists() {
        Set<String> controllers = new TreeSet<>();
        for (JavaClass candidate : APPLICATION) {
            if (candidate.getSimpleName().endsWith("Controller")) {
                controllers.add(candidate.getSimpleName());
            }
        }
        assertThat(controllers).containsAll(CONTROLLERS_STILL_HOLDING_A_REPOSITORY);
    }

    private static ArchCondition<JavaClass> holdARepositoryOnlyIfAlreadyListed() {
        return new ArchCondition<>("reach a repository directly only if already listed as doing so") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                Set<String> repositories = new TreeSet<>();
                for (Dependency dependency : controller.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (target.getPackageName().startsWith(ROOT)
                            && target.getSimpleName().endsWith("Repository")) {
                        repositories.add(target.getSimpleName());
                    }
                }
                boolean listed = CONTROLLERS_STILL_HOLDING_A_REPOSITORY.contains(controller.getSimpleName());
                if (!repositories.isEmpty() && !listed) {
                    events.add(SimpleConditionEvent.violated(controller, controller.getSimpleName()
                            + " reaches " + repositories + " directly. A repository call in a controller "
                            + "is one line away from an unscoped query; go through a service that "
                            + "applies OrganisationAccessService, and do not add this controller to the list."));
                }
                if (repositories.isEmpty() && listed) {
                    events.add(SimpleConditionEvent.violated(controller, controller.getSimpleName()
                            + " no longer holds a repository. Remove it from "
                            + "CONTROLLERS_STILL_HOLDING_A_REPOSITORY so the ratchet tightens."));
                }
            }
        };
    }
}
