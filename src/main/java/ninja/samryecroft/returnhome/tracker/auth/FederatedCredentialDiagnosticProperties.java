package ninja.samryecroft.returnhome.tracker.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the T184 federated-credential diagnostic. Every value is an identifier rather
 * than a secret - tenant, application and issuer ids - which is why they can carry defaults and be
 * read in a log line. The diagnostic is off unless {@code enabled} is set.
 */
@ConfigurationProperties(prefix = "app.auth.fic-diagnostic")
public class FederatedCredentialDiagnosticProperties {

    /**
     * Off by default. Turned on for one ordinary deploy well before cutover, read from the logs, and
     * turned off again - the check is a point-in-time proof of a configuration, not a health check,
     * and leaving it running would put a token request on every restart for no further information.
     */
    private boolean enabled = false;
    /** The audience a managed-identity token must be minted for to be usable as a client assertion. */
    private String exchangeResource = "api://AzureADTokenExchange";
    /** What the credential says it will accept; logged against the token's actual claim. */
    private String expectedIssuer = "https://login.microsoftonline.com/78b88d1e-fe6e-4519-8805-155735aa192e/v2.0";
    private String expectedSubject = "5c5bfae5-b4c4-413b-963a-a5847ca26da6";
    private String tokenEndpoint = "https://1d7a3706-4101-4620-a57d-426e95f41971.ciamlogin.com/"
            + "1d7a3706-4101-4620-a57d-426e95f41971/oauth2/v2.0/token";
    private String clientId = "726d965d-e292-4e29-b6e3-0170ebcc853d";
    private String scope = "https://graph.microsoft.com/.default";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExchangeResource() {
        return exchangeResource;
    }

    public void setExchangeResource(String exchangeResource) {
        this.exchangeResource = exchangeResource;
    }

    public String getExpectedIssuer() {
        return expectedIssuer;
    }

    public void setExpectedIssuer(String expectedIssuer) {
        this.expectedIssuer = expectedIssuer;
    }

    public String getExpectedSubject() {
        return expectedSubject;
    }

    public void setExpectedSubject(String expectedSubject) {
        this.expectedSubject = expectedSubject;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}
