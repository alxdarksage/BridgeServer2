package org.sagebionetworks.bridge.dynamodb;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.ActivityEventDao;
import org.sagebionetworks.bridge.models.activities.ActivityEvent;
import org.sagebionetworks.bridge.models.activities.ActivityEventType;
import org.springframework.stereotype.Component;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper.FailedBatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Lists;

@Component
public class DynamoActivityEventDao implements ActivityEventDao {

    private static final String ANSWERED_EVENT_POSTFIX = ":"+ActivityEventType.ANSWERED.name().toLowerCase();
    private DynamoDBMapper mapper;

    @Resource(name = "activityEventDdbMapper")
    public final void setDdbMapper(DynamoDBMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean deleteCustomEvent(ActivityEvent event) {
        checkNotNull(event);
        
        DynamoActivityEvent hashKey = new DynamoActivityEvent();
        hashKey.setHealthCode(event.getHealthCode());
        hashKey.setStudyId(event.getStudyId());
        hashKey.setEventId(event.getEventId());

        ActivityEvent savedEvent = mapper.load(hashKey);
        if (savedEvent != null) {
            mapper.delete(savedEvent);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean publishEvent(ActivityEvent event) {
        checkNotNull(event);
        
        DynamoActivityEvent hashKey = new DynamoActivityEvent();
        hashKey.setHealthCode(event.getHealthCode());
        hashKey.setStudyId(event.getStudyId());
        hashKey.setEventId(event.getEventId());
        
        ActivityEvent savedEvent = mapper.load(hashKey);
        if (savedEvent == null || isLater(savedEvent, event)) {
            mapper.save(event);
            return true;
        }
        return false;
    }

    @Override
    public Map<String, DateTime> getActivityEventMap(String healthCode, String studyId) {
        checkNotNull(healthCode);
        
        DynamoActivityEvent hashKey = new DynamoActivityEvent();
        hashKey.setHealthCode(healthCode);
        hashKey.setStudyId(studyId);
        DynamoDBQueryExpression<DynamoActivityEvent> query = new DynamoDBQueryExpression<DynamoActivityEvent>()
            .withHashKeyValues(hashKey);

        PaginatedQueryList<DynamoActivityEvent> queryResults = mapper.query(DynamoActivityEvent.class, query);
        
        Builder<String,DateTime> builder = ImmutableMap.<String,DateTime>builder();
        for (DynamoActivityEvent event : queryResults) {
            builder.put(getEventMapKey(event), new DateTime(event.getTimestamp(), DateTimeZone.UTC));
        }
        return builder.build();
    }
    
    @Override
    public void deleteActivityEvents(String healthCode, String studyId) {
        checkNotNull(healthCode);
        
        DynamoActivityEvent hashKey = new DynamoActivityEvent();
        hashKey.setHealthCode(healthCode);
        hashKey.setStudyId(studyId);
        DynamoDBQueryExpression<DynamoActivityEvent> query = new DynamoDBQueryExpression<DynamoActivityEvent>()
            .withHashKeyValues(hashKey);

        PaginatedQueryList<DynamoActivityEvent> queryResults = mapper.query(DynamoActivityEvent.class, query);
        
        List<DynamoActivityEvent> objectsToDelete = Lists.newArrayList();
        objectsToDelete.addAll(queryResults);
        
        if (!objectsToDelete.isEmpty()) {
            List<FailedBatch> failures = mapper.batchDelete(objectsToDelete);
            BridgeUtils.ifFailuresThrowException(failures);
        }
    }
    
    /**
     * Events cannot be recorded unless the timestamp submitted is later than the currently
     * recorded timestamp.
     */
    private boolean isLater(ActivityEvent savedEvent, ActivityEvent event) {
        return event.getTimestamp() > savedEvent.getTimestamp();
    }

    /**
     * Answer events do schedule against a specific answer, which is added to the key in the
     * map only. A change in the value is continued to be a change to the same event.
     * @param event
     * @return
     */
    private String getEventMapKey(DynamoActivityEvent event) {
        if (event.getEventId().endsWith(ANSWERED_EVENT_POSTFIX)) {
            return event.getEventId()+"="+event.getAnswerValue();
        }
        return event.getEventId();    
    }
    
}
