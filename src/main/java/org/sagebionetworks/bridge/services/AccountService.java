package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Boolean.TRUE;
import static org.sagebionetworks.bridge.BridgeConstants.API_MAXIMUM_PAGE_SIZE;
import static org.sagebionetworks.bridge.BridgeConstants.NEGATIVE_OFFSET_ERROR;
import static org.sagebionetworks.bridge.BridgeUtils.filterForStudy;
import static org.sagebionetworks.bridge.dao.AccountDao.MIGRATION_VERSION;
import static org.sagebionetworks.bridge.models.ResourceList.ID_FILTER;
import static org.sagebionetworks.bridge.models.ResourceList.OFFSET_BY;
import static org.sagebionetworks.bridge.models.ResourceList.PAGE_SIZE;
import static org.sagebionetworks.bridge.models.accounts.AccountSecretType.REAUTH;
import static org.sagebionetworks.bridge.models.accounts.AccountStatus.DISABLED;
import static org.sagebionetworks.bridge.models.accounts.AccountStatus.ENABLED;
import static org.sagebionetworks.bridge.models.accounts.AccountStatus.UNVERIFIED;
import static org.sagebionetworks.bridge.models.accounts.PasswordAlgorithm.DEFAULT_PASSWORD_ALGORITHM;
import static org.sagebionetworks.bridge.services.AuthenticationService.ChannelType.EMAIL;
import static org.sagebionetworks.bridge.services.AuthenticationService.ChannelType.PHONE;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.AccountSecretDao;
import org.sagebionetworks.bridge.exceptions.AccountDisabledException;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.models.AccountSummarySearch;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifierInfo;
import org.sagebionetworks.bridge.models.accounts.PasswordAlgorithm;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.services.AuthenticationService.ChannelType;
import org.sagebionetworks.bridge.time.DateUtils;

@Component
public class AccountService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);
    
    static final String PAGE_SIZE_ERROR = "pageSize must be from 1-"+API_MAXIMUM_PAGE_SIZE+" records";
    static final int ROTATIONS = 3;
    
    private AccountDao accountDao;
    private AccountSecretDao accountSecretDao;

    @Autowired
    public final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    
    @Autowired
    public final void setAccountSecretDao(AccountSecretDao accountSecretDao) {
        this.accountSecretDao = accountSecretDao;
    }
    
    // Provided to override in tests
    protected String generateGUID() {
        return BridgeUtils.generateGuid();
    }
    
    /**
     * Search for all accounts across apps that have the same Synapse user ID in common, 
     * and return a list of the app IDs where these accounts are found.
     */
    public List<String> getAppIdsForUser(String synapseUserId) {
        if (StringUtils.isBlank(synapseUserId)) {
            throw new BadRequestException("Account does not have a Synapse user");
        }
        return accountDao.getAppIdForUser(synapseUserId);
    }
    
    /**
     * Set the verified flag for the channel (email or phone) to true, and enable the account (if needed).
     */
    public void verifyChannel(AuthenticationService.ChannelType channelType, Account account) {
        checkNotNull(channelType);
        checkNotNull(account);
        
        // Do not modify the account if it is disabled (all email verification workflows are 
        // user triggered, and disabled means that a user cannot use or change an account).
        if (account.getStatus() == DISABLED) {
            return;
        }
        
        // Avoid updating on every sign in by examining object state first.
        boolean shouldUpdateEmailVerified = (channelType == EMAIL && !TRUE.equals(account.getEmailVerified()));
        boolean shouldUpdatePhoneVerified = (channelType == PHONE && !TRUE.equals(account.getPhoneVerified()));
        boolean shouldUpdateStatus = (account.getStatus() == UNVERIFIED);
        
        if (shouldUpdatePhoneVerified || shouldUpdateEmailVerified || shouldUpdateStatus) {
            if (shouldUpdateEmailVerified) {
                account.setEmailVerified(TRUE);
            }
            if (shouldUpdatePhoneVerified) {
                account.setPhoneVerified(TRUE);
            }
            if (shouldUpdateStatus) {
                account.setStatus(ENABLED);
            }
            account.setModifiedOn(DateUtils.getCurrentDateTime());
            accountDao.updateAccount(account, null);    
        }        
    }
    
    /**
     * Call to change a password, possibly verifying the channel used to reset the password. The channel 
     * type (which is optional, and can be null) is the channel that has been verified through the act 
     * of successfully resetting the password (sometimes there is no channel that is verified). 
     */
    public void changePassword(Account account, ChannelType channelType, String newPassword) {
        checkNotNull(account);
        
        PasswordAlgorithm passwordAlgorithm = DEFAULT_PASSWORD_ALGORITHM;
        
        String passwordHash = hashCredential(passwordAlgorithm, "password", newPassword);

        // Update
        DateTime modifiedOn = DateUtils.getCurrentDateTime();
        account.setModifiedOn(modifiedOn);
        account.setPasswordAlgorithm(passwordAlgorithm);
        account.setPasswordHash(passwordHash);
        account.setPasswordModifiedOn(modifiedOn);
        // One of these (the channel used to reset the password) is also verified by resetting the password.
        if (channelType == EMAIL) {
            account.setStatus(ENABLED);
            account.setEmailVerified(true);    
        } else if (channelType == PHONE) {
            account.setStatus(ENABLED);
            account.setPhoneVerified(true);    
        } else if (channelType == null) {
            // If there's no channel type, we're assuming a password-based sign-in using
            // external ID (the third identifying credential that can be used), so here
            // we will enable the account.
            account.setStatus(ENABLED);
        }
        accountDao.updateAccount(account, null);
    }
    
    /**
     * Authenticate a user with the supplied credentials, returning that user's account record
     * if successful. 
     */
    public Account authenticate(App app, SignIn signIn) {
        checkNotNull(app);
        checkNotNull(signIn);
        
        Account account = accountDao.getAccount(signIn.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException(Account.class));
        verifyPassword(account, signIn.getPassword());
        return authenticateInternal(app, account, signIn);        
    }

    /**
     * Re-acquire a valid session using a special token passed back on an
     * authenticate request. Allows the client to re-authenticate without prompting
     * for a password.
     */
    public Account reauthenticate(App app, SignIn signIn) {
        checkNotNull(app);
        checkNotNull(signIn);
        
        if (!TRUE.equals(app.isReauthenticationEnabled())) {
            throw new UnauthorizedException("Reauthentication is not enabled for app: " + app.getName());    
        }
        Account account = accountDao.getAccount(signIn.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException(Account.class));
        accountSecretDao.verifySecret(REAUTH, account.getId(), signIn.getReauthToken(), ROTATIONS)
            .orElseThrow(() -> new EntityNotFoundException(Account.class));
        return authenticateInternal(app, account, signIn);        
    }
    
    /**
     * This clears the user's reauthentication token.
     */
    public void deleteReauthToken(AccountId accountId) {
        checkNotNull(accountId);

        Account account = getAccount(accountId);
        if (account != null) {
            accountSecretDao.removeSecrets(REAUTH, account.getId());
        }
    }
    
    /**
     * Create an account. If the optional consumer is passed to this method and it throws an 
     * exception, the account will not be persisted (the consumer is executed after the persist 
     * is executed in a transaction, however).
     */
    public void createAccount(App app, Account account, Consumer<Account> afterPersistConsumer) {
        checkNotNull(app);
        checkNotNull(account);
        
        account.setAppId(app.getIdentifier());
        DateTime timestamp = DateUtils.getCurrentDateTime();
        account.setCreatedOn(timestamp);
        account.setModifiedOn(timestamp);
        account.setPasswordModifiedOn(timestamp);
        account.setMigrationVersion(MIGRATION_VERSION);

        // Create account. We don't verify studies because this is handled by validation
        accountDao.createAccount(app, account, afterPersistConsumer);
    }
    
    /**
     * Save account changes. Account should have been retrieved from the getAccount() method 
     * (constructAccount() is not sufficient). If the optional consumer is passed to this method and 
     * it throws an exception, the account will not be persisted (the consumer is executed after 
     * the persist is executed in a transaction, however).
     */
    public void updateAccount(Account account, Consumer<Account> afterPersistConsumer) {
        checkNotNull(account);
        
        AccountId accountId = AccountId.forId(account.getAppId(),  account.getId());

        // Can't change app, email, phone, emailVerified, phoneVerified, createdOn, or passwordModifiedOn.
        Account persistedAccount = accountDao.getAccount(accountId)
                .orElseThrow(() -> new EntityNotFoundException(Account.class));
        // None of these values should be changeable by the user.
        account.setAppId(persistedAccount.getAppId());
        account.setCreatedOn(persistedAccount.getCreatedOn());
        account.setPasswordAlgorithm(persistedAccount.getPasswordAlgorithm());
        account.setPasswordHash(persistedAccount.getPasswordHash());
        account.setPasswordModifiedOn(persistedAccount.getPasswordModifiedOn());
        // This has to be changed via the membership APIs.
        account.setOrgMembership(persistedAccount.getOrgMembership());
        // Update modifiedOn.
        account.setModifiedOn(DateUtils.getCurrentDateTime());

        // Update. We don't verify studies because this is handled by validation
        accountDao.updateAccount(account, afterPersistConsumer);         
    }
    
    /**
     * Load, and if it exists, edit and save an account. Note that constraints are not
     * enforced here (which is intentional).
     */
    public void editAccount(String appId, String healthCode, Consumer<Account> accountEdits) {
        checkNotNull(appId);
        checkNotNull(healthCode);
        
        AccountId accountId = AccountId.forHealthCode(appId, healthCode);
        Account account = getAccount(accountId);
        if (account != null) {
            accountEdits.accept(account);
            accountDao.updateAccount(account, null);
        }        
    }
    
    /**
     * Get an account in the context of a app by the user's ID, email address, health code,
     * or phone number. Returns null if the account cannot be found, or the caller does not have 
     * the correct study associations to access the account. (Other methods in this service 
     * also make a check for study associations by relying on this method internally).
     */
    public Account getAccount(AccountId accountId) {
        checkNotNull(accountId);

        Optional<Account> optional = accountDao.getAccount(accountId);
        if (optional.isPresent()) {
            // filtering based on the study associations of the caller.
            return filterForStudy(optional.get());
        }
        return null;
    }
    
    /**
     * Delete an account along with the authentication credentials.
     */
    public void deleteAccount(AccountId accountId) {
        checkNotNull(accountId);
        
        Optional<Account> opt = accountDao.getAccount(accountId);
        if (opt.isPresent()) {
            accountDao.deleteAccount(opt.get().getId());
        }
    }
    
    /**
     * Get a page of lightweight account summaries (most importantly, the email addresses of 
     * participants which are required for the rest of the participant APIs). 
     * @param appId
     *      retrieve participants in this app
     * @param search
     *      all the parameters necessary to perform a filtered search of user account summaries, including
     *      paging parameters.
     */
    public PagedResourceList<AccountSummary> getPagedAccountSummaries(String appId, AccountSummarySearch search) {
        checkNotNull(appId);
        checkNotNull(search);
        
        return accountDao.getPagedAccountSummaries(appId, search);
    }
    
    /**
     * For MailChimp, and other external systems, we need a way to get a healthCode for a given email.
     */
    public String getHealthCodeForAccount(AccountId accountId) {
        checkNotNull(accountId);
        
        Account account = getAccount(accountId);
        if (account != null) {
            return account.getHealthCode();
        } else {
            return null;
        }
    }
    
    public PagedResourceList<ExternalIdentifierInfo> getAccountSummariesWithExternalIds(String appId, String idFilter,
            Integer offsetBy, Integer pageSize) {
        if (offsetBy != null && offsetBy < 0) {
            throw new BadRequestException(NEGATIVE_OFFSET_ERROR);
        }
        if (pageSize != null && (pageSize < 1 || pageSize > API_MAXIMUM_PAGE_SIZE)) {
            throw new BadRequestException(PAGE_SIZE_ERROR);
        }
        return accountDao.getAccountSummariesWithExternalIds(appId, idFilter, offsetBy, pageSize)
                .withRequestParam(ID_FILTER, idFilter)
                .withRequestParam(OFFSET_BY, offsetBy)
                .withRequestParam(PAGE_SIZE, pageSize);
    }
    
    protected Account authenticateInternal(App app, Account account, SignIn signIn) {
        // Auth successful, you can now leak further information about the account through other exceptions.
        // For email/phone sign ins, the specific credential must have been verified (unless we've disabled
        // email verification for older apps that didn't have full external ID support).
        if (account.getStatus() == UNVERIFIED) {
            throw new UnauthorizedException("Email or phone number have not been verified");
        } else if (account.getStatus() == DISABLED) {
            throw new AccountDisabledException();
        } else if (app.isVerifyChannelOnSignInEnabled()) {
            if (signIn.getPhone() != null && !TRUE.equals(account.getPhoneVerified())) {
                throw new UnauthorizedException("Phone number has not been verified");
            } else if (app.isEmailVerificationEnabled() && 
                    signIn.getEmail() != null && !TRUE.equals(account.getEmailVerified())) {
                throw new UnauthorizedException("Email has not been verified");
            }
        }
        return account;
    }
    
    protected void verifyPassword(Account account, String plaintext) {
        // Verify password
        if (account.getPasswordAlgorithm() == null || StringUtils.isBlank(account.getPasswordHash())) {
            LOG.warn("Account " + account.getId() + " is enabled but has no password.");
            throw new EntityNotFoundException(Account.class);
        }
        try {
            if (!account.getPasswordAlgorithm().checkHash(account.getPasswordHash(), plaintext)) {
                // To prevent enumeration attacks, if the credential doesn't match, throw 404 account not found.
                throw new EntityNotFoundException(Account.class);
            }
        } catch (InvalidKeyException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
            throw new BridgeServiceException("Error validating password: " + ex.getMessage(), ex);
        }        
    }
    
    protected String hashCredential(PasswordAlgorithm algorithm, String type, String value) {
        try {
            return algorithm.generateHash(value);
        } catch (InvalidKeyException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
            throw new BridgeServiceException("Error creating "+type+": " + ex.getMessage(), ex);
        }
    }    
}
