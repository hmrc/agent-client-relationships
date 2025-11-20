# ACR19: Track Agent Authorisation Requests

## Overview

Retrieves a paginated and filtered list of authorisation requests (invitations) for an agent. This is the primary endpoint for agent dashboards to display and manage invitation history. Returns requests with filtering by status and client name, along with metadata including all available client names, available status filters, and total result count.

## API Details

- **API ID**: ACR19
- **Method**: GET
- **Path**: `/agent-client-relationships/agent/{arn}/authorisation-requests`
- **Authentication**: Agent authentication (HMRC-AS-AGENT enrolment required)
- **Audience**: internal
- **Controller**: AuthorisationRequestInfoController
- **Controller Method**: `trackRequests`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | String | Yes | Agent Reference Number - must match authenticated agent |

## Query Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| statusFilter | String | No | Filter by invitation status (Pending, Accepted, Rejected, Expired, Cancelled, Deauthorised, PartialAuth). Note: 'Accepted' includes PartialAuth |
| clientName | String | No | Filter by exact client name (URL encoded) |
| pageNumber | Integer | Yes | Page number for pagination (1-based) |
| pageSize | Integer | Yes | Number of results per page |

## Response

### Success Response (200 OK)

Returns `TrackRequestsResult` with paginated invitations and metadata.

```json
{
  "pageNumber": 1,
  "requests": [
    {
      "invitationId": "A1B2C3D4E5F6",
      "arn": "TARN0000001",
      "service": "HMRC-MTD-IT",
      "clientId": "ABCDE1234567890",
      "clientIdType": "MTDITID",
      "suppliedClientId": "AB123456C",
      "suppliedClientIdType": "NI",
      "clientName": "John Smith",
      "agencyName": "Test Agency Ltd",
      "agencyEmail": "agent@test-agency.com",
      "warningEmailSent": false,
      "expiredEmailSent": false,
      "status": "Pending",
      "relationshipEndedBy": null,
      "clientType": "personal",
      "expiryDate": "2025-12-17",
      "created": "2025-11-17T10:00:00Z",
      "lastUpdated": "2025-11-17T10:00:00Z"
    }
  ],
  "clientNames": ["John Smith", "Jane Doe", "Bob Johnson"],
  "availableFilters": ["Accepted", "Cancelled", "Pending", "Rejected"],
  "filtersApplied": {
    "statusFilter": "Pending"
  },
  "totalResults": 45
}
```

### Error Responses

| Status | Description | Note |
|--------|-------------|------|
| 401 | Unauthorized | Agent authentication failed - missing or invalid credentials |
| 403 | Forbidden | Authenticated agent ARN doesn't match path ARN - agent can only track their own requests |

## Service Architecture

See ACR19.mmd for complete sequence diagram.

### Flow Summary

1. **Authentication**: Validates agent via `withAuthorisedAsAgent` and checks ARN match
2. **Query Building**: Constructs MongoDB aggregation pipeline with filters
3. **Aggregation Execution**: Runs faceted aggregation for efficient data + metadata retrieval
4. **Result Construction**: Builds TrackRequestsResult with paginated invitations and metadata
5. **Response**: Returns comprehensive result set with filter metadata

## Business Logic

### Aggregation Pipeline

Uses MongoDB aggregation with facets for efficient single-query retrieval:

1. **Initial Filter**: Filter all invitations by ARN
2. **Sort**: Order by created timestamp descending (newest first)
3. **Faceted Aggregation**:
   - **clientNamesFacet**: Collects all unique client names for this agent (for filter UI)
   - **availableFiltersFacet**: Collects all unique status values for this agent (for filter UI)
   - **totalResultsFacet**: Counts total results with filters applied (for pagination UI)
   - **requests**: Returns paginated invitation documents with filters, skip, and limit applied

This approach retrieves both the paginated data and the metadata needed for building filter UI in a single database query.

### Status Filtering

Special handling for 'Accepted' status:
- When `statusFilter` is 'Accepted', the query includes both 'Accepted' AND 'PartialAuth' statuses
- This ensures alt-ITSA partial authorisations appear alongside accepted invitations
- All other status filters match exactly

### Client Name Filtering

Client name is URL decoded and then matched against encrypted client name field in database. This allows exact matching on client names while maintaining data encryption at rest.

### Pagination

Standard skip/limit pagination:
- Uses `skip((pageNumber - 1) * pageSize)` and `limit(pageSize)` in the requests facet
- Total results count allows frontend to calculate total pages
- pageNumber is 1-based (first page = 1)

### Filter Metadata

Returns metadata for building filter UI:
- **clientNames array**: All unique client names for dropdown/autocomplete (sorted)
- **availableFilters array**: All status values that exist for this agent (sorted)
- **filtersApplied object**: Shows which filters were actually applied (useful for UI state)

## Dependencies

### External Services

None - purely database query operation

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| InvitationService | trackRequests | Passes through to repository |
| InvitationsRepository | trackRequests | Executes aggregation pipeline |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ (Aggregation) | Complex faceted aggregation: filters by ARN, sorts by created desc, collects metadata (client names, available statuses), counts total, returns paginated results |

## Use Cases

### 1. Agent Views All Pending Invitations

**Scenario**: Agent wants to see all pending authorisation requests

**Flow**:
1. Agent makes GET request with `statusFilter=Pending`, `pageNumber=1`, `pageSize=20`
2. System filters invitations by ARN and Pending status
3. Returns first 20 pending invitations sorted by creation date
4. Includes metadata for building filter UI

**Response**: 200 OK with TrackRequestsResult containing pending invitations

**Frontend Action**: Display pending invitations in table/list. Show pagination controls based on totalResults. Display filter dropdowns using clientNames and availableFilters metadata.

### 2. Agent Searches for Specific Client

**Scenario**: Agent wants to find all invitations for a specific client

**Flow**:
1. Agent enters client name in search box
2. Frontend URL-encodes client name and makes request
3. System filters by exact client name match
4. Returns all invitations for that client

**Response**: 200 OK with TrackRequestsResult filtered by client name

**Frontend Action**: Display all invitations for the searched client. Show which filter is applied in filtersApplied field.

### 3. Agent Views Accepted Invitations with Pagination

**Scenario**: Agent wants to review all accepted authorisations

**Flow**:
1. Agent selects 'Accepted' filter
2. System includes both Accepted and PartialAuth statuses
3. Returns paginated results
4. Agent can navigate through pages

**Response**: 200 OK with TrackRequestsResult including Accepted and PartialAuth invitations

**Frontend Action**: Display accepted invitations including alt-ITSA partial auths. Provide next/previous page navigation.

### 4. Agent Dashboard Initialization

**Scenario**: Agent opens dashboard and needs to see recent invitations with filter options

**Flow**:
1. Agent opens dashboard
2. Frontend makes request without filters (`pageNumber=1`, `pageSize=20`)
3. System returns recent invitations and all metadata
4. Frontend uses metadata to build filter dropdowns

**Response**: 200 OK with TrackRequestsResult with full metadata

**Frontend Action**: Display recent invitations. Populate status filter dropdown with availableFilters. Populate client name filter/search with clientNames.

## Error Handling

| Error | Response | Note |
|-------|----------|------|
| ARN mismatch | 403 Forbidden | Authenticated agent ARN doesn't match path ARN - agent can only track their own requests |
| Agent not authenticated | 401 Unauthorized | Missing or invalid agent credentials |
| No invitations found | 200 OK with empty array | Returns empty array with totalResults: 0 - not an error condition |

## Important Notes

- ✅ Uses efficient MongoDB aggregation with facets (single query for data + metadata)
- ✅ Returns metadata for building filter UI (clientNames and availableFilters)
- ✅ Sorted by created timestamp descending (newest invitations first)
- ✅ Returns totalResults for calculating pagination UI
- ✅ Empty filters are treated as 'no filter' (returns all invitations for ARN)
- ✅ 'Accepted' status filter includes both Accepted and PartialAuth (for alt-ITSA)
- ⚠️ Agent can only view their own invitations (enforces ARN match with 403 if mismatch)
- ⚠️ Client name must be URL-encoded and matches exactly against encrypted database values
- ⚠️ pageNumber is 1-based (first page is 1, not 0)
- ⚠️ filtersApplied field shows what filters were applied (useful for UI state management)
- ⚠️ Returns 200 with empty array if no results (not 404)

## Related Documentation

- **ACR18**: Get Agent Authorisation Request Info - retrieves single invitation with shareable link
- **ACR20**: Create Invitation - creates the invitations tracked by this endpoint
- **ACR22**: Cancel Invitation - cancels invitations that appear in this list

