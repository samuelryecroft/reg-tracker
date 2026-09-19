package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.core.ConflictException;
import ninja.samryecroft.returnhome.tracker.core.InvalidRequestException;
import ninja.samryecroft.returnhome.tracker.core.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * T378 (CODE-REVIEW-2026-09-18 P0.4). The advice used to map {@code IllegalArgumentException} to a
 * 404 and {@code IllegalStateException} to a 409, application-wide, so an internal bug - a scoping
 * bug included - reached the person as "we can't find that page" and reached nobody else. This pins
 * the decision that only the three typed outcomes in {@code core} are mapped, and that they still
 * are: a refactor that dropped one would turn a legitimate "not found" into a 500, which the
 * MockMvc tests would catch, but a refactor that ADDED a catch-all back would be caught by nothing
 * else.
 */
class GlobalControllerAdviceNoCatchAllGuardTest {

    @Test
    void noUntypedExceptionIsMappedToAClientStatus() {
        assertThat(handledTypes())
                .as("an untyped exception is a bug, and a bug is a 500")
                .doesNotContain(IllegalArgumentException.class, IllegalStateException.class,
                        RuntimeException.class, Exception.class, Throwable.class);
    }

    @Test
    void theThreeTypedOutcomesAreMapped() {
        assertThat(handledTypes())
                .contains(NotFoundException.class, InvalidRequestException.class, ConflictException.class);
    }

    private static Set<Class<?>> handledTypes() {
        Set<Class<?>> types = new HashSet<>();
        for (Method method : GlobalControllerAdvice.class.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler == null) {
                continue;
            }
            types.addAll(Arrays.asList(handler.value()));
            if (handler.value().length == 0) {
                // A handler with no explicit value is typed by its exception parameter.
                types.addAll(Arrays.asList(method.getParameterTypes()));
            }
        }
        return types;
    }
}
