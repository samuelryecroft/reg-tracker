package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.util.List;
import java.util.Map;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link FieldEncryptionHibernateListener} with Hibernate.
 *
 * <p>Hibernate builds its own listener registry, so a Spring {@code @Component} is not enough on its
 * own - it has to be handed over during session-factory bootstrap. Doing it through an
 * {@link Integrator} keeps the listener a normal Spring bean with its dependencies injected, rather
 * than something Hibernate instantiates and cannot wire.
 */
@Configuration(proxyBeanMethods = false)
public class FieldEncryptionHibernateConfig implements HibernatePropertiesCustomizer {

    private final FieldEncryptionHibernateListener listener;

    public FieldEncryptionHibernateConfig(FieldEncryptionHibernateListener listener) {
        this.listener = listener;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.integrator_provider",
                (IntegratorProvider) () -> List.of(new EncryptionIntegrator(listener)));
    }

    private record EncryptionIntegrator(FieldEncryptionHibernateListener listener) implements Integrator {

        @Override
        public void integrate(org.hibernate.boot.Metadata metadata, BootstrapContext bootstrapContext,
                SessionFactoryImplementor sessionFactory) {
            EventListenerRegistry registry =
                    sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
            registry.appendListeners(EventType.PRE_INSERT, listener);
            registry.appendListeners(EventType.PRE_UPDATE, listener);
        }
    }
}
