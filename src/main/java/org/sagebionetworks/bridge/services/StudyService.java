package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.BridgeUtils.typeNameToLabel;
import static org.sagebionetworks.bridge.models.studies.MimeType.HTML;
import static org.sagebionetworks.bridge.models.studies.PasswordPolicy.DEFAULT_PASSWORD_POLICY;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.client.exceptions.SynapseServerException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.MembershipInvitation;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.util.ModelConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.SecureTokenGenerator;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.cache.CacheKey;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.config.BridgeConfigFactory;
import org.sagebionetworks.bridge.dao.StudyDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConstraintViolationException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.GuidVersionHolder;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.IdentifierHolder;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.studies.PasswordPolicy;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyAndUsers;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.templates.Template;
import org.sagebionetworks.bridge.models.templates.TemplateRevision;
import org.sagebionetworks.bridge.models.templates.TemplateType;
import org.sagebionetworks.bridge.models.upload.UploadFieldDefinition;
import org.sagebionetworks.bridge.models.upload.UploadValidationStrictness;
import org.sagebionetworks.bridge.services.email.BasicEmailProvider;
import org.sagebionetworks.bridge.services.email.EmailType;
import org.sagebionetworks.bridge.validators.StudyParticipantValidator;
import org.sagebionetworks.bridge.validators.StudyValidator;
import org.sagebionetworks.bridge.validators.Validate;

@Component("studyService")
@PropertySource("classpath:conf/study-defaults/sms-messages.properties")
public class StudyService {
    private static final Logger LOG = LoggerFactory.getLogger(StudyService.class);

    private static final String BASE_URL = BridgeConfigFactory.getConfig().get("webservices.url");
    static final String CONFIG_STUDY_WHITELIST = "study.whitelist";
    static final String CONFIG_KEY_SUPPORT_EMAIL_PLAIN = "support.email.plain";
    static final String CONFIG_KEY_TEAM_BRIDGE_ADMIN = "team.bridge.admin";
    static final String CONFIG_KEY_TEAM_BRIDGE_STAFF = "team.bridge.staff";
    private static final String VERIFY_STUDY_EMAIL_URL = "%s/vse?study=%s&token=%s&type=%s";
    static final int VERIFY_STUDY_EMAIL_EXPIRE_IN_SECONDS = 60*60*24;
    static final String EXPORTER_SYNAPSE_USER_ID = BridgeConfigFactory.getConfig().getExporterSynapseId(); // copy-paste from website
    static final String SYNAPSE_REGISTER_END_POINT = "https://www.synapse.org/#!NewAccount:";
    private static final String STUDY_PROPERTY = "Study";
    private static final String TYPE_PROPERTY = "type";
    private static final String STUDY_EMAIL_VERIFICATION_URL = "studyEmailVerificationUrl";
    private static final String STUDY_EMAIL_VERIFICATION_EXPIRATION_PERIOD = "studyEmailVerificationExpirationPeriod";
    private static final String IDENTIFIER_PROPERTY = "identifier";
    public static final Set<ACCESS_TYPE> READ_DOWNLOAD_ACCESS = ImmutableSet.of(ACCESS_TYPE.READ, ACCESS_TYPE.DOWNLOAD);

    private Set<String> studyWhitelist;
    private String bridgeSupportEmailPlain;
    private String bridgeAdminTeamId;
    private String bridgeStaffTeamId;
    private CompoundActivityDefinitionService compoundActivityDefinitionService;
    private SendMailService sendMailService;
    private UploadCertificateService uploadCertService;
    private StudyDao studyDao;
    private StudyValidator validator;
    private CacheProvider cacheProvider;
    private SubpopulationService subpopService;
    private NotificationTopicService topicService;
    private EmailVerificationService emailVerificationService;
    private SynapseClient synapseClient;
    private ParticipantService participantService;
    private ExternalIdService externalIdService;
    private SubstudyService substudyService;
    private TemplateMigrationService templateMigrationService;
    private TemplateService templateService;

    // Not defaults, if you wish to change these, change in source. Not configurable per study
    private String studyEmailVerificationTemplate;
    private String studyEmailVerificationTemplateSubject;
    
    @Value("classpath:conf/templates/study-email-verification.txt")
    final void setStudyEmailVerificationTemplate(org.springframework.core.io.Resource resource)
            throws IOException {
        this.studyEmailVerificationTemplate = IOUtils.toString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
    @Value("classpath:conf/templates/study-email-verification-subject.txt")
    final void setStudyEmailVerificationTemplateSubject(
            org.springframework.core.io.Resource resource) throws IOException {
        this.studyEmailVerificationTemplateSubject = IOUtils.toString(resource.getInputStream(),
                StandardCharsets.UTF_8);
    }
    
    /** Bridge config. */
    @Autowired
    public final void setBridgeConfig(BridgeConfig bridgeConfig) {
        this.bridgeSupportEmailPlain = bridgeConfig.get(CONFIG_KEY_SUPPORT_EMAIL_PLAIN);
        this.bridgeAdminTeamId = bridgeConfig.get(CONFIG_KEY_TEAM_BRIDGE_ADMIN);
        this.bridgeStaffTeamId = bridgeConfig.get(CONFIG_KEY_TEAM_BRIDGE_STAFF);
        this.studyWhitelist = Collections.unmodifiableSet(new HashSet<>(
                bridgeConfig.getPropertyAsList(CONFIG_STUDY_WHITELIST)));
    }

    /** Compound activity definition service, used to clean up deleted studies. This is set by Spring. */
    @Autowired
    final void setCompoundActivityDefinitionService(
            CompoundActivityDefinitionService compoundActivityDefinitionService) {
        this.compoundActivityDefinitionService = compoundActivityDefinitionService;
    }

    /** Send mail service, used to send the consent notification email verification email. */
    @Autowired
    public final void setSendMailService(SendMailService sendMailService) {
        this.sendMailService = sendMailService;
    }

    @Resource(name="uploadCertificateService")
    final void setUploadCertificateService(UploadCertificateService uploadCertService) {
        this.uploadCertService = uploadCertService;
    }
    @Autowired
    final void setValidator(StudyValidator validator) {
        this.validator = validator;
    }
    @Autowired
    final void setStudyDao(StudyDao studyDao) {
        this.studyDao = studyDao;
    }
    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }
    @Autowired
    final void setSubpopulationService(SubpopulationService subpopService) {
        this.subpopService = subpopService;
    }
    @Autowired
    final void setNotificationTopicService(NotificationTopicService topicService) {
        this.topicService = topicService;
    }
    @Autowired
    final void setEmailVerificationService(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }
    @Autowired
    final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    @Autowired
    final void setExternalIdService(ExternalIdService externalIdService) {
        this.externalIdService = externalIdService;
    }
    @Autowired
    final void setSubstudyService(SubstudyService substudyService) {
        this.substudyService = substudyService;
    }
    @Autowired
    final void setTemplateMigrationService(TemplateMigrationService templateMigrationService) {
        this.templateMigrationService = templateMigrationService;
    }
    @Autowired
    final void setTemplateService(TemplateService templateService) {
        this.templateService = templateService;
    }
    
    @Autowired
    @Qualifier("bridgePFSynapseClient")
    public final void setSynapseClient(SynapseClient synapseClient) {
        this.synapseClient = synapseClient;
    }
    
    public Study getStudy(String identifier, boolean includeDeleted) {
        checkArgument(isNotBlank(identifier), Validate.CANNOT_BE_BLANK, IDENTIFIER_PROPERTY);

        Study study = cacheProvider.getStudy(identifier);
        if (study == null) {
            study = studyDao.getStudy(identifier);
            cacheProvider.setStudy(study);
        }
        if (study != null) {
            // If it it exists and has been deactivated, and this call is not supposed to retrieve deactivated
            // studies, treat it as if it doesn't exist.
            if (!study.isActive() && !includeDeleted) {
                throw new EntityNotFoundException(Study.class, "Study not found.");
            }
        }
        return study;
    }

    // only return active study
    public Study getStudy(String identifier) {
        if (isBlank(identifier)) {
            throw new BadRequestException("study parameter is required");
        }
        return getStudy(identifier, false);
    }

    public Study getStudy(StudyIdentifier studyId) {
        checkNotNull(studyId, Validate.CANNOT_BE_NULL, "studyIdentifier");
        
        return getStudy(studyId.getIdentifier());
    }

    public List<Study> getStudies() {
        return studyDao.getStudies();
    }

    public Study createStudyAndUsers(StudyAndUsers studyAndUsers) throws SynapseException {
        checkNotNull(studyAndUsers, Validate.CANNOT_BE_NULL, "study and users");

        List<String> adminIds = studyAndUsers.getAdminIds();
        if (adminIds == null || adminIds.isEmpty()) {
            throw new BadRequestException("Admin IDs are required.");
        }
        // validate if each admin id is a valid synapse id in synapse
        for (String adminId : adminIds) {
            try {
                synapseClient.getUserProfile(adminId);
            } catch (SynapseNotFoundException e) {
                throw new BadRequestException("Admin ID is invalid.");
            }
        }

        List<StudyParticipant> users = studyAndUsers.getUsers();
        if (users == null || users.isEmpty()) {
            throw new BadRequestException("User list is required.");
        }
        if (studyAndUsers.getStudy() == null) {
            throw new BadRequestException("Study cannot be null.");
        }
        Study study = studyAndUsers.getStudy();
        // prevent NPE in participant validation
        if (study.getPasswordPolicy() == null) {
            study.setPasswordPolicy(PasswordPolicy.DEFAULT_PASSWORD_POLICY);
        }

        // validate participants first
        for (StudyParticipant user : users) {
            Validate.entityThrowingException(new StudyParticipantValidator(externalIdService, substudyService, study, true), user);
        }

        // validate roles for each user
        for (StudyParticipant user: users) {
            if (!Collections.disjoint(user.getRoles(), ImmutableSet.of(Roles.ADMIN, Roles.WORKER))) {
                throw new BadRequestException("User can only have roles developer and/or researcher.");
            }
            if (user.getRoles().isEmpty()) {
                throw new BadRequestException("User should have at least one role.");
            }
        }

        // then create and validate study
        study = createStudy(study);

        // then create users for that study
        // send verification email from both Bridge and Synapse as well
        for (StudyParticipant user: users) {
            IdentifierHolder identifierHolder = participantService.createParticipant(study, user, false);

            NewUser synapseUser = new NewUser();
            synapseUser.setEmail(user.getEmail());
            try {
                synapseClient.newAccountEmailValidation(synapseUser, SYNAPSE_REGISTER_END_POINT);
            } catch (SynapseServerException e) {
                if (!e.getMessage().contains("The email address provided is already used.")) {
                    throw e;
                } else {
                    LOG.info("Email: " + user.getEmail() + " already exists in Synapse", e);
                }
            }
            // send resetting password email as well
            participantService.requestResetPassword(study, identifierHolder.getIdentifier());
        }

        // finally create synapse project and team
        createSynapseProjectTeam(studyAndUsers.getAdminIds(), study);

        return study;
    }

    public Study createStudy(Study study) {
        checkNotNull(study, Validate.CANNOT_BE_NULL, "study");
        if (study.getVersion() != null){
            throw new EntityAlreadyExistsException(Study.class, "Study has a version value; it may already exist",
                new ImmutableMap.Builder<String,Object>().put(IDENTIFIER_PROPERTY, study.getIdentifier()).build()); 
        }

        study.setActive(true);
        study.setConsentNotificationEmailVerified(false);
        study.setStudyIdExcludedInExport(true);
        study.setVerifyChannelOnSignInEnabled(true);
        study.setEmailVerificationEnabled(true);
        study.getDataGroups().add(BridgeConstants.TEST_USER_GROUP);
        if (study.getPasswordPolicy() == null) {
            study.setPasswordPolicy(DEFAULT_PASSWORD_POLICY);
        }        
        // If validation strictness isn't set on study creation, set it to a reasonable default.
        if (study.getUploadValidationStrictness() == null) {
            study.setUploadValidationStrictness(UploadValidationStrictness.REPORT);
        }

        Validate.entityThrowingException(validator, study);

        if (studyDao.doesIdentifierExist(study.getIdentifier())) {
            throw new EntityAlreadyExistsException(Study.class, IDENTIFIER_PROPERTY, study.getIdentifier());
        }
        
        subpopService.createDefaultSubpopulation(study);
        
        for (TemplateType type: TemplateType.values()) {
            String typeName = type.name().toLowerCase();
            if (study.getDefaultTemplates().get(typeName) == null) {
                Template template = Template.create();
                template.setName(typeNameToLabel(type));
                template.setTemplateType(type);
                GuidVersionHolder keys = templateService.createTemplate(study, template);
                study.getDefaultTemplates().put(typeName, keys.getGuid());               
            }
        }
        // do not create certs for whitelisted studies (legacy studies)
        if (!studyWhitelist.contains(study.getIdentifier())) {
            uploadCertService.createCmsKeyPair(study.getStudyIdentifier());
        }
        
        study = studyDao.createStudy(study);
        cacheProvider.setStudy(study);
        
        emailVerificationService.verifyEmailAddress(study.getSupportEmail());

        if (study.getConsentNotificationEmail() != null) {
            sendVerifyEmail(study, StudyEmailType.CONSENT_NOTIFICATION);    
        }

        return study;
    }

    public Study createSynapseProjectTeam(List<String> synapseUserIds, Study study) throws SynapseException {
        if (synapseUserIds == null || synapseUserIds.isEmpty()) {
            throw new BadRequestException("Synapse User IDs are required.");
        }
        // first check if study already has project and team ids
        if (study.getSynapseDataAccessTeamId() != null){
            throw new EntityAlreadyExistsException(Study.class, "Study already has a team ID.",
                new ImmutableMap.Builder<String,Object>().put(IDENTIFIER_PROPERTY, study.getIdentifier())
                    .put("synapseDataAccessTeamId", study.getSynapseDataAccessTeamId()).build());
        }
        if (study.getSynapseProjectId() != null){
            throw new EntityAlreadyExistsException(Study.class, "Study already has a project ID.",
                new ImmutableMap.Builder<String,Object>().put(IDENTIFIER_PROPERTY, study.getIdentifier())
                .put("synapseProjectId", study.getSynapseProjectId()).build());
        }

        // then check if the user id exists
        for (String userId : synapseUserIds) {
            try {
                synapseClient.getUserProfile(userId);
            } catch (SynapseNotFoundException e) {
                throw new BadRequestException("Synapse User Id: " + userId + " is invalid.");
            }
        }

        // Name in Synapse are globally unique, so we add a random token to the name to ensure it 
        // doesn't conflict with an existing name. Also, Synapse names can only contain a certain 
        // subset of characters.
        String nameScopingToken = getNameScopingToken();
        String synapseName;
        try {
            synapseName = BridgeUtils.toSynapseFriendlyName(study.getName());    
        } catch(NullPointerException | IllegalArgumentException e) {
            throw new BadRequestException("Study name is invalid Synapse name: " + study.getName());
        }
        // create synapse project and team
        Team team = new Team();
        team.setName(synapseName + " Access Team "+nameScopingToken);
        Project project = new Project();
        project.setName(synapseName + " Project "+nameScopingToken);

        Team newTeam = synapseClient.createTeam(team);
        Project newProject = synapseClient.createEntity(project);
        
        // Add the exporter, bridge admin team, and individuals as admins
        AccessControlList projectACL = synapseClient.getACL(newProject.getId());
        addAdminToACL(projectACL, EXPORTER_SYNAPSE_USER_ID); // add exporter as admin
        addAdminToACL(projectACL, bridgeAdminTeamId);
        for (String synapseUserId : synapseUserIds) {
            addAdminToACL(projectACL, synapseUserId);
        }
        // Add the data access team and bridge staff team as a read/download team
        addToACL(projectACL, bridgeStaffTeamId, READ_DOWNLOAD_ACCESS);
        addToACL(projectACL, newTeam.getId(), READ_DOWNLOAD_ACCESS);
        synapseClient.updateACL(projectACL);

        // send invitation to target user for joining new team and grant admin permission to that user.
        // Users added afterwards will have read/download rights through the access team.
        for (String synapseUserId : synapseUserIds) {
            MembershipInvitation teamMemberInvitation = new MembershipInvitation();
            teamMemberInvitation.setInviteeId(synapseUserId);
            teamMemberInvitation.setTeamId(newTeam.getId());
            synapseClient.createMembershipInvitation(teamMemberInvitation, null, null);
            synapseClient.setTeamMemberPermissions(newTeam.getId(), synapseUserId, true);
        }

        String newTeamId = newTeam.getId();
        String newProjectId = newProject.getId();

        // finally, update study
        study.setSynapseProjectId(newProjectId);
        study.setSynapseDataAccessTeamId(Long.parseLong(newTeamId));
        updateStudy(study, false);

        return study;
    }
    
    protected String getNameScopingToken() {
        return SecureTokenGenerator.NAME_SCOPE_INSTANCE.nextToken();
    }
    
    private void addAdminToACL(AccessControlList acl, String principalId) {
        addToACL(acl, principalId, ModelConstants.ENTITY_ADMIN_ACCESS_PERMISSIONS);
    }

    private void addToACL(AccessControlList acl, String principalId, Set<ACCESS_TYPE> accessTypes) {
        ResourceAccess resource = new ResourceAccess();
        resource.setPrincipalId(Long.parseLong(principalId));
        resource.setAccessType(accessTypes);
        acl.getResourceAccess().add(resource);
    }
    
    public Study updateStudy(Study study, boolean isAdminUpdate) {
        checkNotNull(study, Validate.CANNOT_BE_NULL, "study");

        // These cannot be set through the API and will be null here, so they are set on update
        Study originalStudy = studyDao.getStudy(study.getIdentifier());
        
        checkViolationConstraints(originalStudy, study);
        
        // A number of fields can only be set by an administrator. We set these to their existing values if the 
        // caller is not an admin.
        if (!isAdminUpdate) {
            // prevent non-admins update a deactivated study
            if (!originalStudy.isActive()) {
                throw new EntityNotFoundException(Study.class, "Study '"+ study.getIdentifier() +"' not found.");
            }
            study.setHealthCodeExportEnabled(originalStudy.isHealthCodeExportEnabled());
            study.setEmailVerificationEnabled(originalStudy.isEmailVerificationEnabled());
            study.setExternalIdRequiredOnSignup(originalStudy.isExternalIdRequiredOnSignup());
            study.setEmailSignInEnabled(originalStudy.isEmailSignInEnabled());
            study.setPhoneSignInEnabled(originalStudy.isPhoneSignInEnabled());
            study.setReauthenticationEnabled(originalStudy.isReauthenticationEnabled());
            study.setAccountLimit(originalStudy.getAccountLimit());
            study.setStudyIdExcludedInExport(originalStudy.isStudyIdExcludedInExport());
            study.setVerifyChannelOnSignInEnabled(originalStudy.isVerifyChannelOnSignInEnabled());
        }

        // prevent anyone changing active to false -- it should be done by deactivateStudy() method
        if (originalStudy.isActive() && !study.isActive()) {
            throw new BadRequestException("Study cannot be deleted through an update.");
        }

        // With the introduction of the session verification email, studies won't have all the templates
        // that are normally required. So set it if someone tries to update a study, to a default value.
        templateMigrationService.migrateTemplates(study);
        if (study.getPasswordPolicy() == null) {
            study.setPasswordPolicy(DEFAULT_PASSWORD_POLICY);
        }        

        Validate.entityThrowingException(validator, study);

        if (originalStudy.isConsentNotificationEmailVerified() == null) {
            // Studies before the introduction of the consentNotificationEmailVerified flag have it set to null. For
            // backwards compatibility, treat this as "true". If these aren't actually verified, we'll handle it on a
            // case-by-case basis.
            study.setConsentNotificationEmailVerified(true);
        } else if (!originalStudy.isConsentNotificationEmailVerified()) {
            // You can't use the updateStudy() API to set consentNotificationEmailVerified from false to true.
            study.setConsentNotificationEmailVerified(false);
        }
        // This needs to happen before the study is updated.
        boolean consentHasChanged = !Objects.equals(originalStudy.getConsentNotificationEmail(),
                study.getConsentNotificationEmail());
        if (consentHasChanged) {
            study.setConsentNotificationEmailVerified(false);
        }

        // Only admins can delete or modify upload metadata fields. Check this after validation, so we don't have to
        // deal with duplicates.
        // Anyone (admin or developer) can add or re-order fields.
        if (!isAdminUpdate) {
            checkUploadMetadataConstraints(originalStudy, study);
        }

        Study updatedStudy = updateAndCacheStudy(study);
        
        if (!originalStudy.getSupportEmail().equals(study.getSupportEmail())) {
            emailVerificationService.verifyEmailAddress(study.getSupportEmail());
        }
        if (consentHasChanged && study.getConsentNotificationEmail() != null) {
            sendVerifyEmail(study, StudyEmailType.CONSENT_NOTIFICATION);    
        }
        return updatedStudy;
    }

    // Helper method to save the study to the DAO and also update the cache.
    private Study updateAndCacheStudy(Study study) {
        // When the version is out of sync in the cache, then an exception is thrown and the study
        // is not updated in the cache. At least we can delete the study before this, so the next
        // time it should succeed. Have not figured out why they get out of sync.
        cacheProvider.removeStudy(study.getIdentifier());
        Study updatedStudy = studyDao.updateStudy(study);
        cacheProvider.setStudy(updatedStudy);
        return updatedStudy;
    }

    // Helper method to check if we deleted or modified an upload metadata fields. Only admins can delete or modify
    // upload metadata fields.
    private static void checkUploadMetadataConstraints(Study oldStudy, Study newStudy) {
        // Shortcut: if oldStudy.uploadMetadataFieldDefinitions is empty, we can skip. Adding fields is always okay.
        if (oldStudy.getUploadMetadataFieldDefinitions().isEmpty()) {
            return;
        }

        // Field defs are in lists because we care about the order, but for this computation, we want maps.
        Map<String, UploadFieldDefinition> oldFieldMap = Maps.uniqueIndex(oldStudy.getUploadMetadataFieldDefinitions(),
                UploadFieldDefinition::getName);
        Map<String, UploadFieldDefinition> newFieldMap = Maps.uniqueIndex(newStudy.getUploadMetadataFieldDefinitions(),
                UploadFieldDefinition::getName);

        // Determine if any fields were deleted (old minus new)
        Set<String> oldFieldNameSet = oldFieldMap.keySet();
        Set<String> newFieldNameSet = newFieldMap.keySet();
        Set<String> removedFieldNameSet = Sets.difference(oldFieldNameSet, newFieldNameSet);

        // Determine if any fields were changed. First find the overlap (intersection), then for each field name,
        // compare the old field with the new field.
        Set<String> overlapFieldNameSet = Sets.intersection(oldFieldNameSet, newFieldNameSet);
        Set<String> modifiedFieldNameSet = overlapFieldNameSet.stream()
                .filter(fieldName -> !Objects.equals(oldFieldMap.get(fieldName), newFieldMap.get(fieldName)))
                .collect(Collectors.toSet());

        // Use a TreeSet, so we can generate a message in a predictable order.
        Set<String> changedFieldSet = new TreeSet<>();
        changedFieldSet.addAll(removedFieldNameSet);
        changedFieldSet.addAll(modifiedFieldNameSet);

        if (!changedFieldSet.isEmpty()) {
            throw new UnauthorizedException("Non-admins cannot delete or modify upload metadata fields; " +
                    "affected fields: " + BridgeUtils.COMMA_SPACE_JOINER.join(changedFieldSet));
        }
    }

    public void deleteStudy(String identifier, boolean physical) {
        checkArgument(isNotBlank(identifier), Validate.CANNOT_BE_BLANK, IDENTIFIER_PROPERTY);

        if (studyWhitelist.contains(identifier)) {
            throw new UnauthorizedException(identifier + " is protected by whitelist.");
        }

        // only admin can call this method, should contain deactivated ones.
        Study existing = getStudy(identifier, true);
        
        if (!physical) {
            // deactivate
            if (!existing.isActive()) {
                throw new BadRequestException("Study '"+identifier+"' already deactivated.");
            }
            studyDao.deactivateStudy(existing.getIdentifier());
        } else {
            // actual delete
            studyDao.deleteStudy(existing);
            
            // delete study data
            deleteAllTemplates(existing.getStudyIdentifier());
            compoundActivityDefinitionService.deleteAllCompoundActivityDefinitionsInStudy(
                    existing.getStudyIdentifier());
            subpopService.deleteAllSubpopulations(existing.getStudyIdentifier());
            topicService.deleteAllTopics(existing.getStudyIdentifier());
        }

        cacheProvider.removeStudy(identifier);
    }
    
    private void deleteAllTemplates(StudyIdentifier studyId) {
        for (TemplateType type : TemplateType.values()) {
            PagedResourceList<? extends Template> page;
            do {
                page = templateService.getTemplatesForType(studyId, type, 0, 50, true);
                System.out.println(page);
                for (Template template : page.getItems()) {
                    templateService.deleteTemplatePermanently(studyId, template.getGuid());    
                }
            } while(!page.getItems().isEmpty());
        }
    }
    
    /**
     * The user cannot remove data groups or task identifiers. These may be referenced by many objects on the server,
     * and may be used by the client application. If any of these are missing on an update, throw a constraint
     * violation exception.
     */
    private void checkViolationConstraints(Study originalStudy, Study study) {
        if (!study.getDataGroups().containsAll(originalStudy.getDataGroups())) {
            throw new ConstraintViolationException.Builder()
                    .withEntityKey(IDENTIFIER_PROPERTY, study.getIdentifier()).withEntityKey(TYPE_PROPERTY, STUDY_PROPERTY)
                    .withMessage("Data groups cannot be deleted.").build();
        }
        if (!study.getTaskIdentifiers().containsAll(originalStudy.getTaskIdentifiers())) {
            throw new ConstraintViolationException.Builder()
                    .withEntityKey(IDENTIFIER_PROPERTY, study.getIdentifier()).withEntityKey(TYPE_PROPERTY, STUDY_PROPERTY)
                    .withMessage("Task identifiers cannot be deleted.").build();
        }
        if (!study.getActivityEventKeys().containsAll(originalStudy.getActivityEventKeys())) {
            throw new ConstraintViolationException.Builder()
                    .withEntityKey(IDENTIFIER_PROPERTY, study.getIdentifier()).withEntityKey(TYPE_PROPERTY, STUDY_PROPERTY)
                    .withMessage("Activity event keys cannot be deleted.").build();

        }
    }
    
    /** Sends the email verification email for the given study's email. */
    public void sendVerifyEmail(StudyIdentifier studyId, StudyEmailType type) {
        Study study = getStudy(studyId);
        sendVerifyEmail(study, type);
    }

    // Helper method to send the email verification email.
    private void sendVerifyEmail(Study study, StudyEmailType type) {
        checkNotNull(study);
        if (type == null) {
            throw new BadRequestException("Email type must be specified");
        }

        // Figure out which email we need to verify from type.
        String email;
        switch (type) {
            case CONSENT_NOTIFICATION:
                email = study.getConsentNotificationEmail();
                break;
            default:
                // Impossible code path, but put it in for future-proofing.
                throw new BadRequestException("Unrecognized email type \"" + type.toString() + "\"");
        }
        if (email == null) {
            throw new BadRequestException("Email not set for study");
        }

        // Generate and save token.
        String token = createTimeLimitedToken();
        saveVerification(token, new VerificationData(study.getIdentifier(), email));

        // Create and send verification email. Users cannot edit this template so there's no backwards
        // compatibility issues
        String studyId = BridgeUtils.encodeURIComponent(study.getIdentifier());
        String shortUrl = String.format(VERIFY_STUDY_EMAIL_URL, BASE_URL, studyId, token, type.toString().toLowerCase());
        
        TemplateRevision revision = TemplateRevision.create();
        revision.setSubject(studyEmailVerificationTemplateSubject);
        revision.setDocumentContent(studyEmailVerificationTemplate);
        revision.setMimeType(HTML);
        
        BasicEmailProvider provider = new BasicEmailProvider.Builder().withStudy(study).withTemplateRevision(revision)
                .withOverrideSenderEmail(bridgeSupportEmailPlain).withRecipientEmail(email)
                .withToken(STUDY_EMAIL_VERIFICATION_URL, shortUrl)
                .withExpirationPeriod(STUDY_EMAIL_VERIFICATION_EXPIRATION_PERIOD, VERIFY_STUDY_EMAIL_EXPIRE_IN_SECONDS)
                .withType(EmailType.VERIFY_CONSENT_EMAIL)
                .build();
        sendMailService.sendEmail(provider);
    }

    /** Verifies the email with the given verification token. */
    public void verifyEmail(StudyIdentifier studyId, String token, StudyEmailType type) {
        // Verify input.
        checkNotNull(studyId);
        checkNotNull(studyId.getIdentifier());
        if (StringUtils.isBlank(token)) {
            throw new BadRequestException("Verification token must be specified");
        }
        if (type == null) {
            throw new BadRequestException("Email type must be specified");
        }

        // Check token against the cache.
        VerificationData data = restoreVerification(token);
        if (data == null) {
            throw new BadRequestException("Email verification token has expired (or already been used).");
        }

        // Figure out which email we need to verify from type.
        Study study = getStudy(studyId);
        String email;
        switch (type) {
            case CONSENT_NOTIFICATION:
                email = study.getConsentNotificationEmail();
                break;
            default:
                // Impossible code path, but put it in for future-proofing.
                throw new BadRequestException("Unrecognized email type \"" + type.toString() + "\"");
        }

        // Make sure the study's current consent notification email matches the email saved in the verification data.
        // If the study's consent notification email is updated, the caller might still be using an older verification
        // email.
        if (!studyId.getIdentifier().equals(data.getStudyId())) {
            throw new BadRequestException("Email verification token is for a different study.");
        }
        if (email == null || !email.equals(data.getEmail())) {
            throw new BadRequestException("Email verification token does not match consent notification email.");
        }

        // Use type to determine which email to verify.
        switch (type) {
            case CONSENT_NOTIFICATION:
                study.setConsentNotificationEmailVerified(true);
                break;
            default:
                // Impossible code path, but put it in for future-proofing.
                throw new BadRequestException("Unrecognized email type \"" + type.toString() + "\"");
        }

        // Update study.
        updateAndCacheStudy(study);
    }

    // Creates a random token for consent notification email verification. Package-scoped so it can be mocked by unit
    // tests.
    String createTimeLimitedToken() {
        return SecureTokenGenerator.INSTANCE.nextToken();
    }

    // Helper method to save consent notification email verification data from the cache.
    private void saveVerification(String sptoken, VerificationData data) {
        checkArgument(isNotBlank(sptoken));
        checkNotNull(data);

        try {
            CacheKey cacheKey = CacheKey.verificationToken(sptoken);
            cacheProvider.setObject(cacheKey, BridgeObjectMapper.get().writeValueAsString(data),
                    VERIFY_STUDY_EMAIL_EXPIRE_IN_SECONDS);
        } catch (IOException e) {
            throw new BridgeServiceException(e);
        }
    }

    // Helper method to fetch consent notification email verification data from the cache.
    private VerificationData restoreVerification(String sptoken) {
        checkArgument(isNotBlank(sptoken));

        CacheKey cacheKey = CacheKey.verificationToken(sptoken);
        String json = cacheProvider.getObject(cacheKey, String.class);
        if (json != null) {
            try {
                cacheProvider.removeObject(cacheKey);
                return BridgeObjectMapper.get().readValue(json, VerificationData.class);
            } catch (IOException e) {
                throw new BridgeServiceException(e);
            }
        }
        return null;
    }

    // Verification data for consent notification email.
    private static class VerificationData {
        private final String studyId;
        private final String email;

        @JsonCreator
        VerificationData(@JsonProperty("studyId") String studyId, @JsonProperty("email") String email) {
            checkArgument(isNotBlank(studyId));
            checkArgument(isNotBlank(email));
            this.studyId = studyId;
            this.email = email;
        }

        // Study ID that we want to verify email for.
        public String getStudyId() {
            return studyId;
        }

        // Email address that we want to verify.
        public String getEmail() {
            return email;
        }
    }
}
