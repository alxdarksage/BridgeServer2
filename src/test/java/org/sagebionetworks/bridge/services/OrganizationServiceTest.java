package org.sagebionetworks.bridge.services;

import org.mockito.Mock;

import static org.sagebionetworks.bridge.BridgeConstants.NEGATIVE_OFFSET_ERROR;
import static org.sagebionetworks.bridge.BridgeConstants.PAGE_SIZE_ERROR;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.ORG_ADMIN;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.TestConstants.ACCOUNT_ID;
import static org.sagebionetworks.bridge.TestConstants.CREATED_ON;
import static org.sagebionetworks.bridge.TestConstants.MODIFIED_ON;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.TestConstants.USER_ID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.cache.CacheKey;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.OrganizationDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.models.AccountSummarySearch;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.organizations.Organization;

public class OrganizationServiceTest extends Mockito {

    private static final String NAME = "aName";
    private static final String IDENTIFIER = "an-identifier";

    @Mock
    OrganizationDao mockOrgDao;
    
    @Mock
    AccountDao mockAccountDao;
    
    @Mock
    CacheProvider mockCacheProvider;
    
    @Mock
    SessionUpdateService mockSessionUpdateService;
    
    @InjectMocks
    @Spy
    OrganizationService service;
    
    @Captor
    ArgumentCaptor<AccountSummarySearch> searchCaptor;
    
    @Captor
    ArgumentCaptor<Account> accountCaptor;
    
    @BeforeMethod
    public void beforeMethod() {
        MockitoAnnotations.initMocks(this);
        
        when(service.getCreatedOn()).thenReturn(CREATED_ON);
        when(service.getModifiedOn()).thenReturn(MODIFIED_ON);
    }
    
    @AfterMethod
    public void afterMethod() {
        RequestContext.set(RequestContext.NULL_INSTANCE);
    }
    
    @Test
    public void getOrganizations() {
        PagedResourceList<Organization> page = new PagedResourceList<>(
                ImmutableList.of(Organization.create(), Organization.create()), 10);
        when(mockOrgDao.getOrganizations(TEST_APP_ID, 100, 20)).thenReturn(page);
        
        PagedResourceList<Organization> retList = service.getOrganizations(TEST_APP_ID, 100, 20);
        assertEquals(retList.getRequestParams().get("offsetBy"), 100);
        assertEquals(retList.getRequestParams().get("pageSize"), 20);
        assertEquals(retList.getTotal(), Integer.valueOf(10));
        assertEquals(retList.getItems().size(), 2);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = NEGATIVE_OFFSET_ERROR)
    public void getOrganizationsNegativeOffset() {
        service.getOrganizations(TEST_APP_ID, -5, 0);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = PAGE_SIZE_ERROR)
    public void getOrganizationsPageTooSmall() {
        service.getOrganizations(TEST_APP_ID, 0, 0);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = PAGE_SIZE_ERROR)
    public void getOrganizationsPageTooLarge() {
        service.getOrganizations(TEST_APP_ID, 0, 1000);
    }
    
    @Test
    public void createOrganization() {
        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);
        org.setVersion(3L);
        
        when(mockOrgDao.createOrganization(org)).thenReturn(org);

        Organization retValue = service.createOrganization(org);
        assertEquals(retValue.getCreatedOn(), CREATED_ON);
        assertEquals(retValue.getModifiedOn(), CREATED_ON);
        assertNull(retValue.getVersion());
        
        verify(mockOrgDao).createOrganization(org);
    }
    
    @Test(expectedExceptions = EntityAlreadyExistsException.class)
    public void createOrganizationAlreadyExists() {
        Organization existing = Organization.create();
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(existing));
        
        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);

        service.createOrganization(org);
    }

    @Test(expectedExceptions = InvalidEntityException.class)
    public void createOrganizationNotValid() {
        Organization org = Organization.create();
        service.createOrganization(org);
    }
    
    @Test
    public void updateOrganization() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership(IDENTIFIER)
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN)).build());

        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);
        
        Organization existing = Organization.create();
        existing.setAppId(TEST_APP_ID);
        existing.setIdentifier(IDENTIFIER);
        existing.setCreatedOn(CREATED_ON);
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(existing));
        
        when(mockOrgDao.updateOrganization(org)).thenReturn(org);
        
        Organization retValue = service.updateOrganization(org);
        assertEquals(retValue.getAppId(), TEST_APP_ID);
        assertEquals(retValue.getIdentifier(), IDENTIFIER);
        assertEquals(retValue.getName(), NAME);
        assertEquals(retValue.getCreatedOn(), CREATED_ON);
        assertEquals(retValue.getModifiedOn(), MODIFIED_ON);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void updateOrganizationNotFound() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.empty());
        
        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);
        
        service.updateOrganization(org);
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void updateOrganizationNotValid() {
        Organization org = Organization.create();
        
        service.updateOrganization(org);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void updateOrganizationCallerNotInOrg() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership("notInRightOrg")
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN)).build());

        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER))
            .thenReturn(Optional.of(Organization.create()));
        
        service.updateOrganization(org);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void updateOrganizationCallerNotOrgAdmin() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership(IDENTIFIER)
                .withCallerRoles(ImmutableSet.of(RESEARCHER)).build());

        Organization org = Organization.create();
        org.setAppId(TEST_APP_ID);
        org.setIdentifier(IDENTIFIER);
        org.setName(NAME);
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER))
            .thenReturn(Optional.of(Organization.create()));
        
        service.updateOrganization(org);
    }
    
    @Test
    public void getOrganization() {
        Organization org = Organization.create();
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER))
            .thenReturn(Optional.of(org));
        
        Organization retValue = service.getOrganization(TEST_APP_ID, IDENTIFIER);
        assertSame(retValue, org);
        
        verify(mockOrgDao).getOrganization(TEST_APP_ID, IDENTIFIER);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void getOrganizationNotFound() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER))
            .thenReturn(Optional.empty());
        
        service.getOrganization(TEST_APP_ID, IDENTIFIER);
    }
    
    @Test
    public void getOrganizationOpt() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER))
            .thenReturn(Optional.of(Organization.create()));
        
        Optional<Organization> retValue = service.getOrganizationOpt(TEST_APP_ID, IDENTIFIER);
        assertTrue(retValue.isPresent());
    }
    
    @Test
    public void deleteOrganization() {
        Organization org = Organization.create();
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(org));
        
        service.deleteOrganization(TEST_APP_ID, IDENTIFIER);
        
        verify(mockOrgDao).deleteOrganization(org);
        verify(mockCacheProvider).removeObject(CacheKey.orgSponsoredStudies(TEST_APP_ID, IDENTIFIER));
    }

    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void deleteOrganizationNotFound() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.empty());
        
        service.deleteOrganization(TEST_APP_ID, IDENTIFIER);
    }
    
    @Test
    public void getMembers() {
        RequestContext.set(new RequestContext.Builder().withCallerOrgMembership(IDENTIFIER).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        
        PagedResourceList<AccountSummary> page = new PagedResourceList<>(ImmutableList.of(), 0); 
        when(mockAccountDao.getPagedAccountSummaries(eq(TEST_APP_ID), any())).thenReturn(page);
        
        AccountSummarySearch search = new AccountSummarySearch.Builder().withLanguage("en").build();        
        
        PagedResourceList<AccountSummary> retValue =  service.getMembers(TEST_APP_ID, IDENTIFIER, search);
        assertSame(retValue, page);
        
        verify(mockAccountDao).getPagedAccountSummaries(eq(TEST_APP_ID), searchCaptor.capture());
        assertEquals(searchCaptor.getValue().getLanguage(), "en");
        assertEquals(searchCaptor.getValue().getOrgMembership(), IDENTIFIER);
        assertNull(searchCaptor.getValue().isAdminOnly());
    }
    
    @Test
    public void getMembersAsSuperadmin() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        
        PagedResourceList<AccountSummary> page = new PagedResourceList<>(ImmutableList.of(), 0); 
        when(mockAccountDao.getPagedAccountSummaries(eq(TEST_APP_ID), any())).thenReturn(page);
        
        AccountSummarySearch search = new AccountSummarySearch.Builder().withLanguage("en").build();        
        
        PagedResourceList<AccountSummary> retValue =  service.getMembers(TEST_APP_ID, IDENTIFIER, search);
        assertSame(retValue, page);
    }

    @Test(expectedExceptions = UnauthorizedException.class)
    public void getMembersNotAuthorized() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        
        PagedResourceList<AccountSummary> page = new PagedResourceList<>(ImmutableList.of(), 0); 
        when(mockAccountDao.getPagedAccountSummaries(eq(TEST_APP_ID), any())).thenReturn(page);
        
        AccountSummarySearch search = new AccountSummarySearch.Builder().withLanguage("en").build();        
        
        service.getMembers(TEST_APP_ID, IDENTIFIER, search);
    }

    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void getMembersOrganizationNotFound() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.empty());
        
        AccountSummarySearch search = new AccountSummarySearch.Builder().withLanguage("en").build();        
        
        service.getMembers(TEST_APP_ID, IDENTIFIER, search);
    }
    
    @Test
    public void addMember() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN))
                .withCallerOrgMembership(IDENTIFIER).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        
        Account account = Account.create();
        account.setId(USER_ID);
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(account));
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture());
        assertEquals(accountCaptor.getValue().getOrgMembership(), IDENTIFIER);
        
        verify(mockSessionUpdateService).updateOrgMembership(USER_ID, IDENTIFIER);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void addMemberCallerNotInOrg() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership("notInRightOrg")
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void addMemberCallerWrongRole() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership(IDENTIFIER)
                .withCallerRoles(ImmutableSet.of(RESEARCHER)).build());

        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test
    public void addMemberAsSuperadmin() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture());
        
        assertEquals(accountCaptor.getValue().getOrgMembership(), IDENTIFIER);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void addMemberUnauthorized() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }

    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void addMemberOrganizationNotFound() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.empty());
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Account not found.")
    public void addMemberAccountNotFound() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.empty());
        
        service.addMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }

    @Test
    public void removeMember() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership(IDENTIFIER)
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        Account account = Account.create();
        account.setOrgMembership(IDENTIFIER);
        account.setId(USER_ID);
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(account));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture());
        assertNull(accountCaptor.getValue().getOrgMembership());
        
        verify(mockSessionUpdateService).updateOrgMembership(USER_ID, null);
    }
    
    @Test(expectedExceptions = UnauthorizedException.class)
    public void removeMemberWrongOrganization() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership("wrongOrganization")
                .withCallerRoles(ImmutableSet.of(ORG_ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }

    @Test(expectedExceptions = UnauthorizedException.class)
    public void removeMemberWrongRole() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerOrgMembership(IDENTIFIER)
                .withCallerRoles(ImmutableSet.of(DEVELOPER)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test
    public void removeMemberAsSuperadmin() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        Account account = Account.create();
        account.setOrgMembership(IDENTIFIER);
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(account));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
        
        verify(mockAccountDao).updateAccount(accountCaptor.capture());
        assertNull(accountCaptor.getValue().getOrgMembership());
    }

    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = ".*Account is not a member of organization.*")
    public void removeMemberNonmemberOfOrganization() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        Account account = Account.create();
        account.setOrgMembership("some-other-org");
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(account));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = ".*Account is not a member of organization.*")
    public void removeMemberCallerNotAnOrgMember() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(Account.create()));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Organization not found.")
    public void removeMemberOrganizationNotFound() {
        RequestContext.set(new RequestContext.Builder().withCallerOrgMembership(IDENTIFIER).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.empty());

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }

    @Test(expectedExceptions = EntityNotFoundException.class, 
            expectedExceptionsMessageRegExp = "Account not found.")
    public void removeMemberAccountNotFound() {
        RequestContext.set(new RequestContext.Builder().withCallerRoles(ImmutableSet.of(ADMIN)).build());
        
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }

    @Test(expectedExceptions = UnauthorizedException.class, 
            expectedExceptionsMessageRegExp = "Caller is not an admin of.*")
    public void removeMemberNotAuthorized() {
        when(mockOrgDao.getOrganization(TEST_APP_ID, IDENTIFIER)).thenReturn(Optional.of(Organization.create()));
        Account account = Account.create();
        account.setOrgMembership(IDENTIFIER);
        when(mockAccountDao.getAccount(ACCOUNT_ID)).thenReturn(Optional.of(account));

        service.removeMember(TEST_APP_ID, IDENTIFIER, ACCOUNT_ID);
    }
    
    @Test
    public void deleteAllOrganizations() {
        service.deleteAllOrganizations(TEST_APP_ID);
        verify(mockOrgDao).deleteAllOrganizations(TEST_APP_ID);
    }
}
