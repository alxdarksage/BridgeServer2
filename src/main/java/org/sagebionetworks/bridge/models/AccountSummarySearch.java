package org.sagebionetworks.bridge.models;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.joda.time.DateTime;
import org.sagebionetworks.bridge.json.DateTimeDeserializer;
import org.sagebionetworks.bridge.json.DateTimeSerializer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * For filtering administrative accounts, there are three use cases:
 * 
 * 1. For the members of an organization, set the organization ID in these search terms;
 * 2. For unassigned administrative accounts that could be assigned to an organization, 
 *    set admin=true in these search terms, and only unassigned admin accounts will be returned;
 * 3. For backwards compatibility, set admin=false and orgMembership=null, and all accounts
 *    will continue to be returned.
 *    
 * The orgMembership field is not settable by users of the API. It is set depending on the 
 * endpoint that was called (membership or participant APIs).
 */
@JsonDeserialize(builder = AccountSummarySearch.Builder.class)
public final class AccountSummarySearch implements BridgeEntity {
    
    public static final AccountSummarySearch EMPTY_SEARCH = new AccountSummarySearch.Builder().build();
    private final int offsetBy;
    private final int pageSize;
    private final String emailFilter;
    private final String phoneFilter;
    private final Set<String> allOfGroups;
    private final Set<String> noneOfGroups; 
    private final String language;
    private final DateTime startTime;
    private final DateTime endTime;
    
    private final String orgMembership;
    private final Boolean adminOnly;

    private AccountSummarySearch(int offsetBy, int pageSize, String emailFilter, String phoneFilter,
            Set<String> allOfGroups, Set<String> noneOfGroups, String language, DateTime startTime, DateTime endTime,
            String orgId, Boolean adminOnly) {
        this.offsetBy = offsetBy;
        this.pageSize = pageSize;
        this.emailFilter = emailFilter;
        this.phoneFilter = phoneFilter;
        this.allOfGroups = allOfGroups;
        this.noneOfGroups = noneOfGroups;
        this.language = language;
        this.startTime = startTime;
        this.endTime = endTime;
        this.orgMembership = orgId;
        this.adminOnly = adminOnly;
    }

    public int getOffsetBy() {
        return offsetBy;
    }
    public int getPageSize() {
        return pageSize;
    }
    public String getEmailFilter() {
        return emailFilter;
    }
    public String getPhoneFilter() {
        return phoneFilter;
    }
    public Set<String> getAllOfGroups() {
        return allOfGroups;
    }
    public Set<String> getNoneOfGroups() {
        return noneOfGroups;
    }
    public String getLanguage() {
        return language;
    }
    @JsonSerialize(using = DateTimeSerializer.class)
    public DateTime getStartTime() {
        return startTime;
    }
    @JsonSerialize(using = DateTimeSerializer.class)
    public DateTime getEndTime() {
        return endTime;
    }
    @JsonIgnore
    public String getOrgMembership() {
        return orgMembership;
    }
    public Boolean isAdminOnly() {
        return adminOnly;
    }
    
    @Override
    public int hashCode() {
        // When serialized, Joda DateTime objects can change their Chronology and become unequal, even when representing
        // the same time instant in the same time zone. (For example, ISOChronology[America/Los_Angeles]
        // versus ISOChronology[-07:00] if that's the offset at the time of serialization). Using the ISO String
        // representation of the DateTime gives us equality across serialization.
        return Objects.hash(allOfGroups, emailFilter, nullsafeDateString(endTime), language, noneOfGroups, offsetBy,
                pageSize, phoneFilter, nullsafeDateString(startTime), orgMembership, adminOnly);
    }

    @Override
    public boolean equals(Object obj) {
        // When serialized, Joda DateTime objects can change their Chronology and become unequal, even when representing
        // the same time instant in the same time zone. (For example, ISOChronology[America/Los_Angeles]
        // versus ISOChronology[-07:00] if that's the offset at the time of serialization). Using the ISO String
        // representation of the DateTime gives us equality across serialization.
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        AccountSummarySearch other = (AccountSummarySearch) obj;
        return Objects.equals(allOfGroups, other.allOfGroups) && Objects.equals(emailFilter, other.emailFilter)
                && Objects.equals(nullsafeDateString(endTime),  nullsafeDateString(other.endTime))
                && Objects.equals(language, other.language) && Objects.equals(noneOfGroups, other.noneOfGroups)
                && Objects.equals(offsetBy, other.offsetBy) && Objects.equals(pageSize, other.pageSize)
                && Objects.equals(phoneFilter, other.phoneFilter)
                && Objects.equals(nullsafeDateString(startTime), nullsafeDateString(other.startTime))
                && Objects.equals(orgMembership, other.orgMembership)
                && Objects.equals(adminOnly, other.adminOnly);
    }
    
    private String nullsafeDateString(DateTime dateTime) {
        return (dateTime == null) ? null : dateTime.toString();
    }
    
    @Override
    public String toString() {
        return "AccountSummarySearch [offsetBy=" + offsetBy + ", pageSize=" + pageSize + ", emailFilter=" + emailFilter
                + ", phoneFilter=" + phoneFilter + ", allOfGroups=" + allOfGroups + ", noneOfGroups=" + noneOfGroups
                + ", language=" + language + ", startTime=" + startTime + ", endTime=" + endTime + ", orgMembership="
                + orgMembership + ", adminOnly=" + adminOnly + "]";
    }
    
    public static class Builder {
        private Integer offsetBy;
        private Integer pageSize;
        private String emailFilter;
        private String phoneFilter;
        private Set<String> allOfGroups = new HashSet<>();
        private Set<String> noneOfGroups = new HashSet<>();
        private String language;
        private DateTime startTime;
        private DateTime endTime;
        private String orgMembership;
        private Boolean adminOnly;
        
        public Builder withOffsetBy(Integer offsetBy) {
            this.offsetBy = offsetBy;
            return this;
        }
        public Builder withPageSize(Integer pageSize) {
            this.pageSize = pageSize;
            return this;
        }
        public Builder withEmailFilter(String emailFilter) {
            this.emailFilter = emailFilter;
            return this;
        }
        public Builder withPhoneFilter(String phoneFilter) {
            this.phoneFilter = phoneFilter;
            return this;
        }
        public Builder withAllOfGroups(Set<String> allOfGroups) {
            if (allOfGroups != null) {
                this.allOfGroups = allOfGroups;    
            }
            return this;
        }
        public Builder withNoneOfGroups(Set<String> noneOfGroups) {
            if (noneOfGroups != null) {
                this.noneOfGroups = noneOfGroups;    
            }
            return this;
        }
        public Builder withLanguage(String language) {
            this.language = language;
            return this;
        }
        @JsonDeserialize(using = DateTimeDeserializer.class)
        public Builder withStartTime(DateTime startTime) {
            this.startTime = startTime;
            return this;
        }
        @JsonDeserialize(using = DateTimeDeserializer.class)
        public Builder withEndTime(DateTime endTime) {
            this.endTime = endTime;
            return this;
        }
        @JsonIgnore
        public Builder withOrgMembership(String orgId) {
            this.orgMembership = orgId;
            return this;
        }
        public Builder withAdminOnly(Boolean adminOnly) {
            this.adminOnly = adminOnly;
            return this;
        }
        public Builder copyOf(AccountSummarySearch search) {
            this.offsetBy = search.offsetBy;
            this.pageSize = search.pageSize;
            this.emailFilter = search.emailFilter;
            this.phoneFilter = search.phoneFilter;
            this.allOfGroups = search.allOfGroups;
            this.noneOfGroups = search.noneOfGroups;
            this.language = search.language;
            this.startTime = search.startTime;
            this.endTime = search.endTime;
            this.orgMembership = search.orgMembership;
            this.adminOnly = search.adminOnly;
            return this;
        }
        public AccountSummarySearch build() {
            int defaultedOffsetBy = (offsetBy == null) ? 0 : offsetBy;
            int defaultedPageSize = (pageSize == null) ? API_DEFAULT_PAGE_SIZE : pageSize;
            return new AccountSummarySearch(defaultedOffsetBy, defaultedPageSize, emailFilter, phoneFilter, allOfGroups,
                    noneOfGroups, language, startTime, endTime, orgMembership, adminOnly);
        }
    }

}
