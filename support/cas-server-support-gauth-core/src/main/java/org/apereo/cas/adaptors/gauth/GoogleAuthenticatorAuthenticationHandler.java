package org.apereo.cas.adaptors.gauth;

import com.warrenstrange.googleauth.IGoogleAuthenticator;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.adaptors.gauth.token.GoogleAuthenticatorToken;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.otp.repository.credentials.OneTimeTokenCredentialRepository;
import org.apereo.cas.otp.repository.token.OneTimeTokenRepository;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.web.support.WebUtils;

import javax.security.auth.login.AccountExpiredException;
import javax.security.auth.login.AccountNotFoundException;
import javax.security.auth.login.FailedLoginException;
import java.security.GeneralSecurityException;

/**
 * An authentication handler that uses the token provided
 * to authenticator against google authN for MFA.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */

@Slf4j
public class GoogleAuthenticatorAuthenticationHandler extends AbstractPreAndPostProcessingAuthenticationHandler {
    
    private final IGoogleAuthenticator googleAuthenticatorInstance;
    private final OneTimeTokenRepository tokenRepository;
    private final OneTimeTokenCredentialRepository credentialRepository;

    public GoogleAuthenticatorAuthenticationHandler(final String name,
                                                    final ServicesManager servicesManager,
                                                    final PrincipalFactory principalFactory,
                                                    final IGoogleAuthenticator googleAuthenticatorInstance,
                                                    final OneTimeTokenRepository tokenRepository,
                                                    final OneTimeTokenCredentialRepository credentialRepository) {
        super(name, servicesManager, principalFactory, null);
        this.googleAuthenticatorInstance = googleAuthenticatorInstance;
        this.tokenRepository = tokenRepository;
        this.credentialRepository = credentialRepository;
    }

    @Override
    protected AuthenticationHandlerExecutionResult doAuthentication(final Credential credential) throws GeneralSecurityException, PreventedException {
        final var tokenCredential = (GoogleAuthenticatorTokenCredential) credential;

        if (!StringUtils.isNumeric(tokenCredential.getToken())) {
            throw new PreventedException("Invalid non-numeric OTP format specified.",
                new IllegalArgumentException("Invalid token " + tokenCredential.getToken()));
        }
        final var otp = Integer.parseInt(tokenCredential.getToken());
        LOGGER.debug("Received OTP [{}]", otp);

        @NonNull
        final var authentication = WebUtils.getInProgressAuthentication();
        final var uid = authentication.getPrincipal().getId();

        LOGGER.debug("Received principal id [{}]. Attempting to locate account in credential repository...", uid);
        final var acct = this.credentialRepository.get(uid);
        if (acct == null || StringUtils.isBlank(acct.getSecretKey())) {
            throw new AccountNotFoundException(uid + " cannot be found in the registry");
        }

        LOGGER.debug("Attempting to locate OTP token [{}] in token repository for [{}]...", otp, uid);
        if (this.tokenRepository.exists(uid, otp)) {
            throw new AccountExpiredException(uid + " cannot reuse OTP " + otp + " as it may be expired/invalid");
        }

        LOGGER.debug("Attempting to authorize OTP token [{}]...", otp);
        var isCodeValid = this.googleAuthenticatorInstance.authorize(acct.getSecretKey(), otp);

        if (!isCodeValid && acct.getScratchCodes().contains(otp)) {
            LOGGER.warn("Using scratch code [{}] to authenticate user [{}]. Scratch code will be removed", otp, uid);
            acct.getScratchCodes().removeIf(token -> token == otp);
            this.credentialRepository.update(acct);
            isCodeValid = true;
        }

        if (isCodeValid) {
            LOGGER.debug("Validated OTP token [{}] successfully for [{}]", otp, uid);
            this.tokenRepository.store(new GoogleAuthenticatorToken(otp, uid));
            LOGGER.debug("Creating authentication result and building principal for [{}]", uid);
            return createHandlerResult(tokenCredential, this.principalFactory.createPrincipal(uid));
        }

        LOGGER.warn("Authorization of OTP token [{}] has failed", otp);
        throw new FailedLoginException("Failed to authenticate code " + otp);
    }

    @Override
    public boolean supports(final Credential credential) {
        return GoogleAuthenticatorTokenCredential.class.isAssignableFrom(credential.getClass());
    }
}
