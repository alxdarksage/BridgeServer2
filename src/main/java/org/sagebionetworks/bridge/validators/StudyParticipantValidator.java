package org.sagebionetworks.bridge.validators;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.Roles.ADMIN;

import java.util.Optional;
import java.util.Set;

import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import org.sagebionetworks.bridge.AuthUtils;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.accounts.Phone;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.apps.PasswordPolicy;
import org.sagebionetworks.bridge.models.organizations.Organization;
import org.sagebionetworks.bridge.models.studies.Enrollment;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.OrganizationService;
import org.sagebionetworks.bridge.services.StudyService;

public class StudyParticipantValidator implements Validator {
    
    static final String ENROLLMENT_REQ_FOR_STUDIES = "must now be replaced with an Enrollment containing an external ID and study ID, or an organizational membership";
    static final String ENROLLMENT_REQ_FOR_EXTID = "must now be replaced with an Enrollment containing an external ID and study ID";

    private static final EmailValidator EMAIL_VALIDATOR = EmailValidator.getInstance();
    private final StudyService studyService;
    private final OrganizationService organizationService;
    private final App app;
    private final boolean isNew;
    
    public StudyParticipantValidator(StudyService studyService, OrganizationService organizationService, App app,
            boolean isNew) {
        this.studyService = studyService;
        this.organizationService = organizationService;
        this.app = app;
        this.isNew = isNew;
    }
    
    @Override
    public boolean supports(Class<?> clazz) {
        return StudyParticipant.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object object, Errors errors) {
        StudyParticipant participant = (StudyParticipant)object;
        
        if (!participant.getStudyIds().isEmpty()) {
            errors.rejectValue("studyIds", ENROLLMENT_REQ_FOR_STUDIES);
        }
        if (isNotBlank(participant.getExternalId())) {
            errors.rejectValue("externalId", ENROLLMENT_REQ_FOR_EXTID);
        }
        
        if (isNew) {
            Phone phone = participant.getPhone();
            String email = participant.getEmail();
            String synapseUserId = participant.getSynapseUserId();
            Enrollment enrollment = participant.getEnrollment();
            if (email == null && enrollment == null && phone == null && isBlank(synapseUserId)) {
                errors.reject("email, phone, synapseUserId or an enrollment is required");
            }
            // If provided, phone must be valid
            if (phone != null && !Phone.isValid(phone)) {
                errors.rejectValue("phone", "does not appear to be a phone number");
            }
            // If provided, email must be valid
            if (email != null && !EMAIL_VALIDATOR.isValid(email)) {
                errors.rejectValue("email", "does not appear to be an email address");
            }
            // Password is optional, but validation is applied if supplied, any time it is 
            // supplied (such as in the password reset workflow).
            String password = participant.getPassword();
            if (password != null) {
                PasswordPolicy passwordPolicy = app.getPasswordPolicy();
                ValidatorUtils.validatePassword(errors, passwordPolicy, password);
            }
            
            // After account creation, organizational membership cannot be changed by updating an account
            // Instead, use the EnrollmentService
            if (enrollment != null) {
                Set<String> orgStudies = RequestContext.get().getOrgSponsoredStudies();
                boolean isAdmin = RequestContext.get().isInRole(ADMIN);
                
                errors.pushNestedPath("enrollment");
                if (isBlank(enrollment.getStudyId())) {
                    errors.rejectValue("studyId", "cannot be blank");
                } else if (!isAdmin && !orgStudies.contains(enrollment.getStudyId())) {
                    errors.rejectValue("studyId", "is not a study of the caller");
                } else {
                    Study study = studyService.getStudy(app.getIdentifier(), enrollment.getStudyId(), false);
                    if (study == null) {
                        errors.rejectValue("studyId", "is not a study");
                    }
                }
                if (enrollment.getExternalId() != null && isBlank(enrollment.getExternalId())) {
                    errors.rejectValue("externalId", "cannot be blank");
                }
                errors.popNestedPath();
            }

            
            // After account creation, organizational membership cannot be changed by updating an account
            // Instead, use the OrganizationService
            if (isNotBlank(participant.getOrgMembership())) {
                String orgId = participant.getOrgMembership();
                Optional<Organization> opt = organizationService.getOrganizationOpt(app.getIdentifier(), orgId);
                if (!opt.isPresent()) {
                    errors.rejectValue("orgMembership", "is not a valid organization");
                } else if (!AuthUtils.isInOrganization(orgId)) {
                    errors.rejectValue("orgMembership", "cannot be set by caller");
                }
            }
            
        } else {
            if (isBlank(participant.getId())) {
                errors.rejectValue("id", "is required");
            }
        }
        
        if (participant.getSynapseUserId() != null && isBlank(participant.getSynapseUserId())) {
            errors.rejectValue("synapseUserId", "cannot be blank");
        }
                
        for (String dataGroup : participant.getDataGroups()) {
            if (!app.getDataGroups().contains(dataGroup)) {
                errors.rejectValue("dataGroups", messageForSet(app.getDataGroups(), dataGroup));
            }
        }
        for (String attributeName : participant.getAttributes().keySet()) {
            if (!app.getUserProfileAttributes().contains(attributeName)) {
                errors.rejectValue("attributes", messageForSet(app.getUserProfileAttributes(), attributeName));
            }
        }
    }
    
    private String messageForSet(Set<String> set, String fieldName) {
        return String.format("'%s' is not defined for app (use %s)", 
                fieldName, BridgeUtils.COMMA_SPACE_JOINER.join(set));
    }
}
