package org.sagebionetworks.bridge.hibernate;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.sagebionetworks.bridge.dao.StudyDao;
import org.sagebionetworks.bridge.models.VersionHolder;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyId;

import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

@Component
public class HibernateStudyDao implements StudyDao {
    private HibernateHelper hibernateHelper;
    
    @Resource(name = "studyHibernateHelper")
    final void setHibernateHelper(HibernateHelper hibernateHelper) {
        this.hibernateHelper = hibernateHelper;
    }

    @Override
    public List<Study> getStudies(String appId, boolean includeDeleted) {
        checkNotNull(appId);
        
        Map<String,Object> parameters = ImmutableMap.of("appId", appId);
        String query = "from HibernateStudy as study where appId=:appId";
        if (!includeDeleted) {
            query += " and deleted != 1";
        }
        return ImmutableList.copyOf(hibernateHelper.queryGet(query, parameters, 
                null, null, HibernateStudy.class));
    }

    @Override
    public Study getStudy(String appId, String id) {
        checkNotNull(appId);
        checkNotNull(id);

        StudyId studyId = new StudyId(appId, id);
        return hibernateHelper.getById(HibernateStudy.class, studyId);
    }
    
    @Override
    public VersionHolder createStudy(Study study) {
        checkNotNull(study);
        
        hibernateHelper.create(study, null);
        return new VersionHolder(study.getVersion());
    }

    @Override
    public VersionHolder updateStudy(Study study) {
        checkNotNull(study);
        
        hibernateHelper.update(study, null);
        return new VersionHolder(study.getVersion());
    }

    @Override
    public void deleteStudyPermanently(String appId, String id) {
        checkNotNull(appId);
        checkNotNull(id);
        
        StudyId studyId = new StudyId(appId, id);
        hibernateHelper.deleteById(HibernateStudy.class, studyId);
    }
}