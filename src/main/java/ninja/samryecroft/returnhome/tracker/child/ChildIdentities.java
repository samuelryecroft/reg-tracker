package ninja.samryecroft.returnhome.tracker.child;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk helper for wiring {@link ChildIdentity} into a list-page controller: one resolved identity
 * per child, keyed by id, so a template reads {@code childIdentities[x.child.id].label} instead of
 * {@code x.child.fullName} - kept separate from {@link ChildIdentity} itself so that record stays
 * exactly what Kevin's review specified, a pure {@code (Child, boolean) -> ChildIdentity} function
 * with no list/map machinery of its own.
 */
public final class ChildIdentities {

    private ChildIdentities() {}

    /**
     * @param items whatever the list page is actually iterating (children directly, interview
     *     requests, anything with a child reachable off it)
     * @param childOf how to reach the {@link Child} from one item
     * @param revealed the viewer's resolved reveal state for this render (never per-item - the
     *     whole page is masked or revealed together)
     */
    public static <T> Map<Long, ChildIdentity> mapOf(List<T> items, Function<T, Child> childOf, boolean revealed) {
        return items.stream()
                .map(childOf)
                .collect(Collectors.toMap(Child::getId, child -> ChildIdentity.of(child, revealed),
                        // Several rows can share the same child (e.g. more than one interview
                        // request against them); same child + same reveal state always resolves to
                        // the same identity, so keeping either duplicate is correct.
                        (a, b) -> a));
    }
}
