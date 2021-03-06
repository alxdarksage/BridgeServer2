package org.sagebionetworks.bridge.hibernate;

import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.models.templates.TemplateType.EMAIL_APP_INSTALL_LINK;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;

import org.joda.time.DateTime;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.Criteria;
import org.sagebionetworks.bridge.models.templates.Template;

public class HibernateTemplateTest extends Mockito {
    
    private static final DateTime CREATED_ON = DateTime.parse("2019-06-10T21:21:04Z");
    private static final DateTime MODIFIED_ON = DateTime.parse("2019-06-19T21:21:04Z");
    private static final DateTime PUBLISHED_CREATED_ON = DateTime.parse("2019-06-12T21:21:04Z");

    @Test
    public void canSerialize() throws Exception {
        Criteria criteria = TestUtils.createCriteria(1, 5, ImmutableSet.of(), ImmutableSet.of());
        
        HibernateTemplate template = new HibernateTemplate();
        template.setAppId(TEST_APP_ID);
        template.setGuid("oneGuid");
        template.setTemplateType(EMAIL_APP_INSTALL_LINK);
        template.setName("oneName");
        template.setDescription("oneDescription");
        template.setCriteria(criteria);
        template.setCreatedOn(CREATED_ON);
        template.setModifiedOn(MODIFIED_ON);
        template.setPublishedCreatedOn(PUBLISHED_CREATED_ON);
        template.setDeleted(true);
        template.setVersion(3);
        
        JsonNode node = BridgeObjectMapper.get().valueToTree(template);
        assertNull(node.get("appId"));
        assertEquals(node.get("guid").textValue(), "oneGuid");
        assertEquals(node.get("templateType").textValue(), EMAIL_APP_INSTALL_LINK.name().toLowerCase());
        assertEquals(node.get("name").textValue(), "oneName");
        assertEquals(node.get("description").textValue(), "oneDescription");
        assertEquals(node.get("createdOn").textValue(), CREATED_ON.toString());
        assertEquals(node.get("modifiedOn").textValue(), MODIFIED_ON.toString());
        assertEquals(node.get("publishedCreatedOn").textValue(), PUBLISHED_CREATED_ON.toString());
        assertTrue(node.get("deleted").booleanValue());
        assertEquals(node.get("version").longValue(), 3L);
        assertEquals(node.get("type").textValue(), "Template");
        
        JsonNode criteriaNode = node.get("criteria");
        assertEquals(criteriaNode.get("minAppVersions").get("iPhone OS").intValue(), 1);
        assertEquals(criteriaNode.get("maxAppVersions").get("iPhone OS").intValue(), 5);
        
        Template deser = BridgeObjectMapper.get().readValue(node.toString(), Template.class);
        assertEquals(deser.getGuid(), "oneGuid");
        assertEquals(deser.getTemplateType(), EMAIL_APP_INSTALL_LINK);
        assertEquals(deser.getName(), "oneName");
        assertEquals(deser.getDescription(), "oneDescription");
        assertEquals(deser.getCriteria(), criteria);
        assertEquals(deser.getCreatedOn(), CREATED_ON);
        assertEquals(deser.getModifiedOn(), MODIFIED_ON);
        assertEquals(deser.getPublishedCreatedOn(), PUBLISHED_CREATED_ON);
        assertTrue(deser.isDeleted());
        assertEquals(deser.getVersion(), 3);
    }

}
