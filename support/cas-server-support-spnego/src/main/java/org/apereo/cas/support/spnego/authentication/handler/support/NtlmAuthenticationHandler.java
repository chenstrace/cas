package org.apereo.cas.support.spnego.authentication.handler.support;

import jcifs.Config;
import jcifs.UniAddress;
import jcifs.netbios.NbtAddress;
import jcifs.ntlmssp.Type1Message;
import jcifs.ntlmssp.Type2Message;
import jcifs.ntlmssp.Type3Message;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.BasicCredentialMetaData;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.DefaultAuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.spnego.authentication.principal.SpnegoCredential;

import javax.security.auth.login.FailedLoginException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Implementation of an AuthenticationHandler for NTLM supports.
 *
 * @author <a href="mailto:julien.henry@capgemini.com">Julien Henry</a>
 * @author Scott Battaglia
 * @author Arnaud Lesueur
 * @since 3.1
 */
@Slf4j
public class NtlmAuthenticationHandler extends AbstractPreAndPostProcessingAuthenticationHandler {


    private static final int NBT_ADDRESS_TYPE = 0x1C;
    private static final int NTLM_TOKEN_TYPE_FIELD_INDEX = 8;
    private static final int NTLM_TOKEN_TYPE_ONE = 1;
    private static final int NTLM_TOKEN_TYPE_THREE = 3;

    private static final String DEFAULT_DOMAIN_CONTROLLER = Config.getProperty("jcifs.smb.client.domain");

    private boolean loadBalance = true;

    /**
     * Sets domain controller. Will default if none is defined or passed.
     */
    private final String domainController;

    private final String includePattern;

    public NtlmAuthenticationHandler(final String name, final ServicesManager servicesManager, final PrincipalFactory principalFactory,
                                     final boolean loadBalance, final String domainController, final String includePattern) {
        super(name, servicesManager, principalFactory, null);
        this.loadBalance = loadBalance;
        if (StringUtils.isBlank(domainController)) {
            this.domainController = DEFAULT_DOMAIN_CONTROLLER;
        } else {
            this.domainController = domainController;
        }
        this.includePattern = includePattern;
    }

    @Override
    protected AuthenticationHandlerExecutionResult doAuthentication(final Credential credential) throws GeneralSecurityException {
        final var ntlmCredential = (SpnegoCredential) credential;
        final var src = ntlmCredential.getInitToken();

        final UniAddress dc;
        var success = false;
        try {
            if (this.loadBalance) {
                if (StringUtils.isNotBlank(this.includePattern)) {
                    final var dcs = NbtAddress.getAllByName(this.domainController, NBT_ADDRESS_TYPE, null, null);
                    dc = Arrays.stream(dcs)
                            .filter(dc2 -> dc2.getHostAddress().matches(this.includePattern))
                            .findFirst()
                            .map(UniAddress::new)
                            .orElse(null);
                } else {
                    dc = new UniAddress(NbtAddress.getByName(this.domainController, NBT_ADDRESS_TYPE, null));
                }
            } else {
                dc = UniAddress.getByName(this.domainController, true);
            }
            final var challenge = SmbSession.getChallenge(dc);

            switch (src[NTLM_TOKEN_TYPE_FIELD_INDEX]) {
                case NTLM_TOKEN_TYPE_ONE:
                    LOGGER.debug("Type 1 received");
                    final var type1 = new Type1Message(src);
                    final var type2 = new Type2Message(type1,
                            challenge, null);
                    LOGGER.debug("Type 2 returned. Setting next token.");
                    ntlmCredential.setNextToken(type2.toByteArray());
                    break;
                case NTLM_TOKEN_TYPE_THREE:
                    LOGGER.debug("Type 3 received");
                    final var type3 = new Type3Message(src);
                    final var lmResponse = type3.getLMResponse() == null ? new byte[0] : type3.getLMResponse();
                    final var ntResponse = type3.getNTResponse() == null ? new byte[0] : type3.getNTResponse();
                    final var ntlm = new NtlmPasswordAuthentication(
                            type3.getDomain(), type3.getUser(), challenge,
                            lmResponse, ntResponse);
                    LOGGER.debug("Trying to authenticate [{}] with domain controller", type3.getUser());
                    try {
                        SmbSession.logon(dc, ntlm);
                        ntlmCredential.setPrincipal(this.principalFactory.createPrincipal(type3.getUser()));
                        success = true;
                    } catch (final SmbAuthException sae) {
                        throw new FailedLoginException(sae.getMessage());
                    }
                    break;
                default:
                    LOGGER.debug("Unknown type: [{}]", src[NTLM_TOKEN_TYPE_FIELD_INDEX]);
            }
        } catch (final Exception e) {
            throw new FailedLoginException(e.getMessage());
        }

        if (!success) {
            throw new FailedLoginException();
        }
        return new DefaultAuthenticationHandlerExecutionResult(this, new BasicCredentialMetaData(ntlmCredential), ntlmCredential.getPrincipal());
    }

    @Override
    public boolean supports(final Credential credential) {
        return credential instanceof SpnegoCredential;
    }
}
