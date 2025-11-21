# ACR24: Get Customer Status Summary

## Overview

This endpoint provides a comprehensive summary of the client's relationship status, returning three boolean flags that indicate the client's current state with respect to agent invitations and relationships. The endpoint is designed for client-facing frontends to display appropriate messages about pending invitations and existing relationships.

⚠️ **MEDIUM COMPLEXITY** - This endpoint checks multiple data sources (invitations, partial auth, IRV relationships, ETMP) and includes sophisticated logic for filtering suspended agents and caching results.

## API Details

- **API ID**: ACR24
- **Method**: GET
- **Path**: `/agent-client-relationships/customer-status`
- **Authentication**: Client authentication required (must have NINO and enrolments)
- **Audience**: Internal (called by client-facing frontends)
- **Controller**: CustomerStatusController
- **Controller Method**: `customerStatus`

## Path Parameters

None

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

```json
{
  "hasPendingInvitations": true,
  "hasInvitationsHistory": true,
  "hasExistingRelationships": true
}
```

| Field | Type | Description |
|-------|------|-------------|
| hasPendingInvitations | Boolean | True if client has any pending invitations from non-suspended agents |
| hasInvitationsHistory | Boolean | True if client has any invitations (any status) or partial auth records (active or inactive) |
| hasExistingRelationships | Boolean | True if client has active partial auth, IRV relationship, PartialAuth/Accepted invitation status, or active ETMP relationships |

### Error Responses

| Status Code | Description |
|-------------|-------------|
| 401 | Unauthorized - Client authentication failed |
| 500 | Internal Server Error - Agent Assurance API failure, ETMP query failure, or database error |

## Service Architecture

See [ACR24.mmd](ACR24.mmd) for complete sequence diagram.

### Flow Summary

1. **Authentication**: Client must be authenticated with NINO and enrolments
2. **Invitation Check**: Retrieve all invitations matching client's services and identifiers
3. **Agent Filter**: Filter out invitations from suspended agents via Agent Assurance API
4. **Partial Auth Check**: Query partial auth records by NINO (if client has NINO)
5. **IRV Check**: Query Agent FI Relationship for Personal Income Record relationships (if client has NINO)
6. **Existing Relationships**:
   - Short-circuit if: active partial auth OR IRV relationship OR PartialAuth/Accepted invitation exists
   - Otherwise: Check cache for existing relationships (15 min TTL)
   - Cache miss: Query ETMP via HIP for all supported services
7. **Build Response**: Calculate three boolean flags based on above checks

## Business Logic

### Invitation Filtering

All invitations are filtered to exclude those from suspended agents:
- Retrieve all invitations for client's services and identifiers
- Extract unique ARNs from invitations
- Call Agent Assurance API for each ARN to check suspension status
- Filter out invitations from suspended agents
- Suspended agent invitations are completely hidden from the response

### hasPendingInvitations Logic

Returns `true` if:
- Any invitation (after filtering suspended agents) has `status = Pending`

Returns `false` otherwise.

### hasInvitationsHistory Logic

Returns `true` if:
- Any invitations exist (any status, from non-suspended agents), **OR**
- Any partial auth records exist (active or inactive)

Returns `false` if no invitations and no partial auth records.

### hasExistingRelationships Logic (Most Complex)

This field uses short-circuit evaluation to avoid expensive ETMP queries when possible:

**Short-circuit returns `true` if:**
1. Any **active** partial auth records exist, **OR**
2. IRV relationship exists (from Agent FI Relationship API), **OR**
3. Any invitation has status `PartialAuth` or `Accepted`

**If no short-circuit conditions met:**
1. Check cache (key = concatenated service+identifier pairs)
2. If cache hit: return cached value
3. If cache miss:
   - Query ETMP via HIP for all supported services
   - Return `true` if any active relationships found
   - Cache result (15 min TTL)

### Caching Strategy

- **Cache Key**: Concatenated service+identifier pairs (lowercase, spaces removed)
  - Example: `hmrc-mtd-it__XMIT00000000001,hmrc-mtd-vat__123456789`
- **TTL**: 15 minutes
- **Purpose**: Reduce load on ETMP for repeated customer status checks
- **Cache Bypass**: Cache is only used if short-circuit conditions are not met

## Dependencies

### External Services

| Service | Method | Purpose | Error Handling |
|---------|--------|---------|----------------|
| Agent Assurance (API 2007) | GET /agent-record/{arn} | Check agent suspension status | Failure causes 500 error |
| Agent FI Relationship | GET /agent-fi-relationship/relationships/active | Check IRV relationships | Errors swallowed, defaults to false |
| IF/HIP | GET /agent-client-relationships/client/relationships/active | Query ETMP for active relationships | Failure causes 500 error |
| ETMP | Via HIP | Source of truth for relationships | Accessed through HIP |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| InvitationService | findNonSuspendedClientInvitations | Find and filter invitations |
| InvitationsRepository | findAllBy | Query invitations from MongoDB |
| AgentAssuranceService | getNonSuspendedAgentRecord | Check agent suspension status |
| PartialAuthRepository | findByNino | Retrieve partial auth records |
| AgentFiRelationshipConnector | findIrvActiveRelationshipForClient | Check IRV relationships |
| FindRelationshipsService | getActiveRelationshipsForClient | Query ETMP relationships |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ | Retrieve invitations by service and clientId |
| partial-auth | READ | Retrieve partial auth records by NINO |
| customer-status-existing-relationships-cache | READ/WRITE | Cache ETMP relationship query results (15 min TTL) |

## Use Cases

### 1. Client with Pending Invitations and Active Relationships

**Scenario**: Client has pending invitation and active partial auth

**Flow**:
1. Client authenticated with NINO and MTD-IT enrolment
2. Invitation found with status = Pending from non-suspended agent
3. Active partial auth record found
4. Short-circuit: hasExistingRelationships = true (no ETMP call needed)

**Response**:
```json
{
  "hasPendingInvitations": true,
  "hasInvitationsHistory": true,
  "hasExistingRelationships": true
}
```

**Frontend Action**: Display "You have pending invitations and existing relationships"

### 2. Client with Invitation from Suspended Agent

**Scenario**: Client has invitation but agent is suspended

**Flow**:
1. Client authenticated
2. Invitation found but agent is suspended (Agent Assurance check)
3. Invitation filtered out
4. No other invitations or relationships

**Response**:
```json
{
  "hasPendingInvitations": false,
  "hasInvitationsHistory": false,
  "hasExistingRelationships": false
}
```

**Frontend Action**: Display "You have no pending invitations" (suspended agent invitation is hidden)

### 3. New Client with No History

**Scenario**: Client has never interacted with agent services

**Flow**:
1. Client authenticated
2. No invitations found
3. No partial auth records
4. No IRV relationship
5. Cache miss - queries ETMP via HIP
6. No active relationships in ETMP
7. Result cached for 15 minutes

**Response**:
```json
{
  "hasPendingInvitations": false,
  "hasInvitationsHistory": false,
  "hasExistingRelationships": false
}
```

**Frontend Action**: Display "You have no invitations or relationships"

### 4. Client with Inactive Partial Auth History

**Scenario**: Client had partial auth that was later deactivated

**Flow**:
1. Client authenticated
2. Inactive partial auth record found
3. No active relationships
4. Queries ETMP - no active relationships

**Response**:
```json
{
  "hasPendingInvitations": false,
  "hasInvitationsHistory": true,
  "hasExistingRelationships": false
}
```

**Frontend Action**: Display "You have invitation history but no active relationships"

### 5. Client with IRV Relationship Only

**Scenario**: Client has Personal Income Record relationship

**Flow**:
1. Client authenticated with NINO
2. No invitations or partial auth
3. Agent FI Relationship returns active IRV relationship
4. Short-circuit: hasExistingRelationships = true (no ETMP call)

**Response**:
```json
{
  "hasPendingInvitations": false,
  "hasInvitationsHistory": false,
  "hasExistingRelationships": true
}
```

**Frontend Action**: Display "You have existing relationships"

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Client not authenticated | 401 Unauthorized | Authentication failed | Must be authenticated as client with enrolments |
| Agent Assurance API failure | 500 Internal Server Error | Error checking agent suspension | Request fails if agent assurance check fails |
| Agent FI Relationship failure | No error | N/A | Errors are swallowed, defaults to no IRV relationship |
| HIP/ETMP query failure | 500 Internal Server Error | Error retrieving relationships | Only called if short-circuit conditions not met |
| MongoDB query failure | 500 Internal Server Error | Database error | Failures in invitations or partial auth queries fail the request |

## Important Notes

- ✅ Client authentication required - must have NINO and enrolments
- ✅ Filters out invitations from suspended agents - these are completely hidden from clients
- ✅ Uses 15-minute caching for existing relationships check to reduce load on ETMP
- ✅ Short-circuits ETMP query if active partial auth, IRV, or PartialAuth/Accepted invitation exists
- ✅ Checks multiple relationship sources: partial auth, IRV, invitations, ETMP
- ✅ hasInvitationsHistory includes both active and inactive partial auth records
- ✅ Cache key is based on all client identifiers (service+identifier pairs)
- ⚠️ Agent Assurance check failure will cause entire request to fail (no graceful degradation)
- ⚠️ IRV relationship check failures are swallowed (defaults to no IRV relationship)
- ⚠️ Cache is per-client based on their identifiers - different services = different cache key
- ⚠️ ETMP query can be slow - hence the caching and short-circuit logic
- ⚠️ Suspended agent invitations are completely hidden from the response - clients never see them
- ⚠️ The logic for hasExistingRelationships has special condition: returns true if ANY active partial auth OR IRV relationship exists, even without checking ETMP

## Related Documentation

- **ACR01**: Check Relationship - Checks if a specific relationship exists
- **ACR02**: Get Inactive Relationships - Retrieves terminated relationships
- **ACR04**: Get All Invitations - Lists all invitations for an agent
- **ACR09**: Get Active Relationships - Retrieves all active relationships for a client (more detailed version)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
