package com.calendar;

import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.AuthenticationFlowException;
import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.FormActionFactory;
import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Registration step that creates the wely-users user as soon as the Keycloak user exists,
 * so that login only has to read it.
 *
 * <p>Runs after "Registration User Profile Creation", inside the transaction that created the
 * Keycloak user. If wely-users fails, that transaction is marked rollback-only before the step
 * fails: Keycloak turns the exception into an error page and would otherwise commit the account.
 */
public class BusinessUserRegistration implements FormAction, FormActionFactory {

    public static final String PROVIDER_ID = "wely-users-registration";
    static final String UNAVAILABLE_MESSAGE = "welyRegistrationUnavailable";

    private static final Logger log = LoggerFactory.getLogger(BusinessUserRegistration.class);

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    private final UserProvisioner provisioner;

    /** Used by Keycloak, which loads providers through {@link java.util.ServiceLoader}. */
    public BusinessUserRegistration() {
        this(UsersServiceClient.fromEnvironment());
    }

    BusinessUserRegistration(UserProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void success(FormContext context) {
        UserModel user = context.getUser();
        try {
            String businessId = provisioner.provision(
                    user.getId(), user.getUsername(), user.getFirstName(), user.getLastName());
            user.setSingleAttribute(BusinessIdMapper.BUSINESS_ID_ATTRIBUTE, businessId);
            log.info("Registered {} as business id {}", user.getId(), businessId);
        } catch (IOException e) {
            log.error("wely-users could not register {}: {}", user.getId(), e.getMessage());
            context.getSession().getTransactionManager().setRollbackOnly();
            throw new AuthenticationFlowException(AuthenticationFlowError.GENERIC_AUTHENTICATION_ERROR,
                    e.getMessage(), UNAVAILABLE_MESSAGE);
        }
    }

    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        // Nothing to add to the form.
    }

    @Override
    public void validate(ValidationContext context) {
        // The Keycloak user does not exist yet here; the work happens in success().
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No required action.
    }

    @Override
    public FormAction create(KeycloakSession session) {
        return this;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Wely users registration";
    }

    @Override
    public String getHelpText() {
        return "Creates the matching wely-users user, and cancels the registration if it cannot";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public void init(Config.Scope config) {
        // No configuration.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to wire.
    }

    @Override
    public void close() {
        // Holds no resources.
    }
}
