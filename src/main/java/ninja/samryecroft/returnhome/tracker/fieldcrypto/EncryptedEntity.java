package ninja.samryecroft.returnhome.tracker.fieldcrypto;

/**
 * An entity with {@link Encrypted} fields, able to say which organisation owns it.
 *
 * <p>The entity resolves its own organisation from the domain model - {@code InterviewRequest ->
 * Home -> Organisation}, the same walk the document path uses - and never from anything a requester
 * supplied. That independence is the point: it means an application-layer scoping bug yields a
 * failed decrypt rather than another organisation's record, because the key is chosen by a
 * different route than the access check that let the request through.
 */
public interface EncryptedEntity {

    /**
     * @return the owning organisation, or {@code null} if it cannot be resolved - which callers
     *         must treat as a failure, never as "encrypt under some default"
     */
    Long owningOrganisationId();
}
