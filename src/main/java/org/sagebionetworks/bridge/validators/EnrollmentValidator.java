package org.sagebionetworks.bridge.validators;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sagebionetworks.bridge.validators.Validate.CANNOT_BE_NULL_OR_EMPTY;

import org.apache.commons.lang3.StringUtils;
import org.springframework.validation.Errors;

import org.sagebionetworks.bridge.models.studies.Enrollment;
import org.sagebionetworks.bridge.services.StudyService;

public class EnrollmentValidator extends AbstractValidator {
    private StudyService studyService;
    
    public EnrollmentValidator(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Override
    public void validate(Object target, Errors errors) {
        Enrollment enrollment = (Enrollment)target;

        if (StringUtils.isBlank(enrollment.getAppId())) {
            errors.rejectValue("appId", CANNOT_BE_NULL_OR_EMPTY);
        }
        if (StringUtils.isBlank(enrollment.getAccountId())) {
            errors.rejectValue("userId", CANNOT_BE_NULL_OR_EMPTY);
        }
        if (StringUtils.isBlank(enrollment.getStudyId())) {
            errors.rejectValue("studyId", CANNOT_BE_NULL_OR_EMPTY);
        }
        if (StringUtils.isNotBlank(enrollment.getAppId()) && StringUtils.isNotBlank(enrollment.getStudyId())) {
            if (studyService.getStudy(enrollment.getAppId(), enrollment.getStudyId(), false) == null) {
                errors.rejectValue("studyId", "is not a study");
            }    
        }
        if (enrollment.getExternalId() != null && isBlank(enrollment.getExternalId())) {
            errors.rejectValue("externalId", "cannot be blank");
        }        
    }

}
