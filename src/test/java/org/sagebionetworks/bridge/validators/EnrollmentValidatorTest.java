package org.sagebionetworks.bridge.validators;

import static org.sagebionetworks.bridge.TestConstants.CREATED_ON;
import static org.sagebionetworks.bridge.TestConstants.MODIFIED_ON;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_ID;
import static org.sagebionetworks.bridge.TestConstants.USER_ID;
import static org.sagebionetworks.bridge.TestUtils.assertValidatorMessage;
import static org.sagebionetworks.bridge.validators.Validate.CANNOT_BE_NULL_OR_EMPTY;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.models.studies.Enrollment;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.StudyService;

public class EnrollmentValidatorTest extends Mockito {
    
    @Mock
    private StudyService mockStudyService;
    
    private EnrollmentValidator validator;
    
    Enrollment getEnrollment() {
        Enrollment enrollment = Enrollment.create(TEST_APP_ID, TEST_STUDY_ID, USER_ID);
        enrollment.setExternalId("anExternalId");
        enrollment.setConsentRequired(true);
        enrollment.setEnrolledOn(CREATED_ON);
        enrollment.setWithdrawnOn(MODIFIED_ON);
        enrollment.setEnrolledBy(USER_ID);
        enrollment.setWithdrawnBy("withdrawnBy");
        enrollment.setWithdrawalNote("withdrawal note");
        return enrollment;
    }
    
    @BeforeMethod
    public void beforeMethod() {
        MockitoAnnotations.initMocks(this);
        validator = new EnrollmentValidator(mockStudyService);
        when(mockStudyService.getStudy(TEST_APP_ID, TEST_STUDY_ID, false)).thenReturn(Study.create());
    }
    
    @Test
    public void validates() {
        Validate.entityThrowingException(validator, getEnrollment());
    }

    @Test
    public void appIdNull() {
        Enrollment enrollment = getEnrollment();
        enrollment.setAppId(null);
        assertValidatorMessage(validator, enrollment, "appId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void appIdBlank() {
        Enrollment enrollment = getEnrollment();
        enrollment.setAppId("");
        assertValidatorMessage(validator, enrollment, "appId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void accountIdNull() {
        Enrollment enrollment = getEnrollment();
        enrollment.setAccountId(null);
        assertValidatorMessage(validator, enrollment, "userId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void accountIdBlank() {
        Enrollment enrollment = getEnrollment();
        enrollment.setAccountId("  ");
        assertValidatorMessage(validator, enrollment, "userId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void studyIdNull() {
        Enrollment enrollment = getEnrollment();
        enrollment.setStudyId(null);
        assertValidatorMessage(validator, enrollment, "studyId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void studyIdBlank() {
        Enrollment enrollment = getEnrollment();
        enrollment.setStudyId("");
        assertValidatorMessage(validator, enrollment, "studyId", CANNOT_BE_NULL_OR_EMPTY);
    }
    
    @Test
    public void externalIdBlank() {
        Enrollment enrollment = getEnrollment();
        enrollment.setExternalId("");
        assertValidatorMessage(validator, enrollment, "externalId", "cannot be blank");
    }
    
    @Test
    public void externalIdNullOK() {
        Enrollment enrollment = getEnrollment();
        enrollment.setExternalId(null);
        Validate.entityThrowingException(validator, getEnrollment());
    }
}
