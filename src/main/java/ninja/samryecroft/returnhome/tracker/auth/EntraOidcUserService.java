package ninja.samryecroft.returnhome.tracker.auth;

import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Turns a validated Entra token into one of our users, or refuses the sign-in.
 *
 * <p>Provisioning is invite-only (§3): an {@code ORG_ADMIN} creates the account and records the
 * person's directory object id, so by the time anyone signs in the link already exists. There is no
 * just-in-time account creation and no first-login matching ceremony - D4 withdrew that, because
 * matching a verified email would <em>bind</em> an Entra identity to an existing enabled account,
 * and whoever could get an account provisioned in the tenant bearing that address would inherit the
 * application account behind it.
 *
 * <p>So this class only ever looks up. It never creates, and it never writes.
 */
@Service
public class EntraOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /**
     * One message for "no such account" and for "account disabled", deliberately.
     *
     * <p>Telling the two apart would answer "does this person have an account on the children's
     * safeguarding system?" for anyone who can authenticate to the tenant - a staff-list oracle we
     * would rather not confirm. It is also the more useful message: in an invite-only system both
     * cases have the same remedy, which is to ask an administrator.
     */
    static final String REFUSED = "Your account is not set up for this application. Contact an administrator.";

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserRepository userRepository;

    @Autowired
    public EntraOidcUserService(UserRepository userRepository) {
        // The stock service is what validates the token and fetches userinfo. We are not replacing
        // that - only deciding, afterwards, which of our users it corresponds to.
        this(userRepository, new OidcUserService());
    }

    /** Lets a test supply the token this class is deciding about, without a live tenant. */
    EntraOidcUserService(UserRepository userRepository, OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.userRepository = userRepository;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser token = delegate.loadUser(userRequest);
        User user = userRepository.findByIdpSubject(objectIdOf(token))
                .filter(User::isEnabled)
                .orElseThrow(() -> refuse());
        return new EntraUserPrincipal(user, token.getIdToken(), token.getUserInfo());
    }

    /**
     * The directory object id ({@code oid}), not {@code sub}.
     *
     * <p>This is the claim choice the whole ID-upfront design rests on, so it is worth being explicit
     * rather than leaving the schema's "{@code sub} (or {@code oid})" hedge to be resolved by
     * whoever reads it next. {@code sub} is pairwise: Entra derives it per (user, application), so
     * it differs between app registrations and - decisively - <b>cannot be looked up in the portal
     * at all</b>. An administrator recording a person's identifier before that person has ever
     * signed in can only obtain {@code oid}, which is what the portal shows as Object ID and what is
     * stable for that user across every application in the tenant.
     *
     * <p>The hedge made sense while P4 was a first-login matching ceremony, where capturing whatever
     * claim arrived was fine. D4 removed the ceremony and made the identifier something a human
     * types in beforehand, which settles it.
     *
     * <p><b>{@code oid} alone is a sufficient key only because we are single-tenant (D1), and that
     * is the same fact that makes a {@code tid} allow-list unnecessary.</b> One condition governs
     * both, so they would have to change in the same moment: if multi-tenant is ever adopted, the
     * key becomes {@code tid} + {@code oid} <em>and</em> validating {@code tid} against an
     * allow-list becomes mandatory - {@code oid} is only unique within a tenant, and without the
     * allow-list any Entra tenant in the world could present a token this application accepts. The
     * allow-list is the obvious half; this one is the half that would be forgotten, which is why the
     * two are written down together.
     *
     * <p>A token with no {@code oid} is refused rather than falling back to {@code sub}: a fallback
     * would look up a value nothing ever stored, and the interesting failure is not "no match" but
     * matching the wrong row later, once someone does store a {@code sub}-shaped value.
     */
    private String objectIdOf(OidcUser token) {
        String objectId = token.getIdToken().getClaimAsString("oid");
        if (objectId == null || objectId.isBlank()) {
            throw refuse();
        }
        return objectId;
    }

    private OAuth2AuthenticationException refuse() {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied", REFUSED, null), REFUSED);
    }
}
