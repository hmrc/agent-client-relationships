# ACR22: ITSA Post-Signup Create Relationship

## Overview

⚠️ **HIGH COMPLEXITY ENDPOINT** - Creates an ITSA (MTD-IT) relationship for a client after they have signed up for Making Tax Digital. This endpoint handles **two distinct flows**:

1. **Partial Auth Conversion**: Converts existing partial authorisation to full relationship if partial auth exists
2. **Legacy SA Copy**: Copies legacy SA (Self Assessment) relationship from CESA/DES to MTD-IT if no partial auth but legacy relationship exists

Used during client MTD signup to establish agent-client relationship automatically, avoiding need for client to re-authorise agent they already work with.

## API Details

- **API ID**: ACR22
- **Method**: POST
- **Path**: `/agent-client-relationships/itsa-post-signup/create-relationship/{nino}`
- **Authentication**: Agent authentication (HMRC-AS-AGENT enrolment required)
- **Audience**: internal
- **Controller**: ItsaPostSignupController
- **Controller Method**: `itsaPostSignupCreateRelationship`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| nino | String | Yes | Client's National Insurance Number |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (201 Created)

Returns JSON with service field indicating which ITSA service relationship was created.

```json
{
  "service": "HMRC-MTD-IT"
}
```

or

```json
{
  "service": "HMRC-MTD-IT-SUPP"
}
```

### Error Responses

| Status | Description | Note |
|--------|-------------|------|
| 401 | Unauthorized | Agent authentication failed |
| 404 | Not Found - no MTDITID found | Client not registered for MTD or NINO invalid |
| 404 | Not Found - no partial auth | Partial auth doesn't exist (when copy-across not attempted) |
| 404 | Not Found - no relationships | Neither partial auth nor legacy SA relationship exists |
| 500 | Internal Server Error | Relationship creation failed or locked |

## Service Architecture

See ACR22.mmd for complete sequence diagram with two distinct flows.

### Flow Summary

**Phase 1 - MTDITID Lookup** (All Cases):
1. Agent authenticates
2. Call HIP to convert NINO to MTDITID
3. If MTDITID not found, return 404

**Phase 2A - Partial Auth Flow** (If Partial Auth Exists):
1. Find active partial auth for nino+arn
2. Create relationship in EACD (allocate enrolment) and ETMP
3. Delete partial auth record
4. Update invitation from PartialAuth to Accepted status
5. Clean up duplicate same-agent relationships
6. Return 201 with service from partial auth

**Phase 2B - Legacy SA Copy Flow** (If No Partial Auth):
1. Check eligibility for copy (not MTD-IT-SUPP, no partial auth requested)
2. Call DES to get legacy SA agent references
3. If references exist, create MTD-IT relationship in EACD and ETMP
4. Clean up duplicate same-agent relationships
5. Return 201 with HMRC-MTD-IT

**Phase 2C - No Relationships Found**:
- Return 404 - client needs to explicitly authorise agent

## Business Logic

### MTDITID Lookup

Converts NINO to MTDITID at the start by calling HIP/IF `getMtdIdFor(nino)`. MTDITID is required for MTD-IT enrolment key construction. If not found, returns 404 immediately - client must complete MTD registration first.

### Flow Selection

The endpoint tries two approaches in order:

1. **Partial Auth Conversion** (Priority 1): Checks for active partial auth for this nino+arn combination
   - If found, creates relationship using partial auth service type
   - Partial auth takes precedence over legacy SA

2. **Legacy SA Copy** (Priority 2): Only attempted if:
   - No partial auth exists
   - Service is not MTD-IT-SUPP (supplementary not copied)
   - No partial auth was previously requested for this client-agent

3. **No Relationships**: If neither exists, returns 404

### Partial Auth Flow Details

**Purpose**: Convert existing partial authorisation to full relationship

**Steps**:
1. Find active partial auth record (contains service, nino, arn)
2. Create full relationship using `CreateRelationshipsService`:
   - Get agent's principal group ID from EACD
   - Create relationship-copy-record for tracking
   - Allocate MTD-IT enrolment to agent's group in EACD
   - Create agent relationship in ETMP (HIP)
   - Delete relationship-copy-record
   - Uses `failIfAllocateAgentInESFails=true` for stricter error handling
3. On success:
   - Delete partial auth record from database
   - Update invitation status from PartialAuth to Accepted (includes MTDITID)
   - Clean up duplicate same-agent relationships
4. Return 201 with service from partial auth (HMRC-MTD-IT or HMRC-MTD-IT-SUPP)

**Significance**: Completes the partial auth journey - client initially gave partial consent (limited scope), now receives full MTD-IT relationship upon MTD signup.

### Legacy SA Copy Flow Details

**Purpose**: Migrate legacy Self Assessment relationship to MTD-IT

**Steps**:
1. Check eligibility (no partial auth, not MTD-IT-SUPP)
2. Call DES `getClientSaAgentSaReferences(nino)` to get legacy SA relationships
3. If SA references exist, create MTD-IT relationship:
   - Same process as partial auth flow but service is always HMRC-MTD-IT
   - Includes SA references for validation/audit
4. Clean up duplicate same-agent relationships
5. Return 201 with HMRC-MTD-IT

**Significance**: Allows seamless migration from legacy SA system to MTD-IT without client having to re-authorise their existing agent.

### Relationship Creation Mechanism

Uses `CreateRelationshipsService.createRelationship` which:
- Creates relationship in both EACD (enrolment allocation) and ETMP (agent relationship)
- Uses recovery mechanism with relationship-copy-record tracking
- Atomic across EACD and ETMP (both must succeed)
- `failIfAllocateAgentInESFails=true` ensures EACD errors are not ignored

### Same-Agent Cleanup

After successful relationship creation, calls `ItsaDeauthAndCleanupService.deleteSameAgentRelationship` to remove any pre-existing duplicate relationships between this agent and client for the same service. Prevents issues with multiple relationship records.

### Eligibility for Copy-Across

Legacy SA copy is **NOT** eligible if:
- Partial auth exists (takes priority)
- Service requested is MTD-IT-SUPP (supplementary not migrated)
- Partial auth was previously requested (indicates client intent for partial flow)

Only copies when none of these exclusion criteria apply.

## Dependencies

### External Services

| Service | Methods | Purpose | Flow |
|---------|---------|---------|------|
| HIP/IF | getMtdIdFor, createAgentRelationship | Convert NINO to MTDITID (start). Create agent relationship in ETMP (during creation) | Both flows |
| DES (CESA) | getClientSaAgentSaReferences | Retrieve legacy SA agent references | Legacy SA copy flow only |
| EACD | getPrincipalGroupIdFor, allocateEnrolmentToAgent | Get agent's group ID and allocate MTD-IT enrolment | Both flows |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| CheckAndCopyRelationshipsService | tryCreateITSARelationshipFromPartialAuthOrCopyAcross | Orchestrates both flows - tries partial auth, then legacy SA |
| CreateRelationshipsService | createRelationship | Creates relationship in EACD and ETMP with recovery |
| ItsaDeauthAndCleanupService | deleteSameAgentRelationship | Cleans up duplicate relationships |
| AuditService | sendAuditEvent | Sends audit events for relationship creation |

### Database Collections

| Collection | Operation | Description | Flow |
|------------|-----------|-------------|------|
| partial-auth | READ/DELETE | Finds active partial auth. Deletes after successful relationship creation | Partial auth flow only |
| invitations | UPDATE | Updates invitation status from PartialAuth to Accepted with MTDITID | Partial auth flow only |
| relationship-copy-record | INSERT/DELETE | Tracks relationship creation progress for recovery. Created at start, deleted on completion | Both flows |

## Use Cases

### 1. Client Signs Up for MTD-IT with Existing Partial Auth

**Scenario**: Client previously gave partial authorisation to agent, now completes MTD signup

**Request**:
```
POST /itsa-post-signup/create-relationship/AB123456C
```

**Flow**:
1. Agent authenticates
2. System gets MTDITID for NINO via HIP
3. System finds active partial auth for this agent+client
4. Creates full MTD-IT relationship in EACD and ETMP
5. Deletes partial auth record
6. Updates invitation to Accepted status
7. Cleans up any duplicate relationships

**Response**:
```json
201 Created
{
  "service": "HMRC-MTD-IT"
}
```

**Frontend Action**: Show success message "Relationship created. Partial authorisation has been converted to full MTD-IT access." Agent now has complete access to client's MTD-IT records.

### 2. Client Signs Up with Legacy SA Relationship

**Scenario**: Client has legacy SA relationship with agent, no partial auth exists

**Request**:
```
POST /itsa-post-signup/create-relationship/AB123456C
```

**Flow**:
1. Agent authenticates
2. System gets MTDITID
3. No partial auth found
4. System checks DES for legacy SA relationships
5. Finds SA agent references
6. Creates MTD-IT relationship by copying SA relationship
7. Cleans up duplicates

**Response**:
```json
201 Created
{
  "service": "HMRC-MTD-IT"
}
```

**Frontend Action**: Show "Relationship created. Legacy SA relationship has been migrated to MTD-IT." Agent maintains access under new digital system without client needing to re-authorise.

### 3. Client Signs Up But No Prior Relationship Exists

**Scenario**: Client has neither partial auth nor legacy SA relationship with this agent

**Request**:
```
POST /itsa-post-signup/create-relationship/AB123456C
```

**Flow**:
1. Agent authenticates
2. System gets MTDITID
3. No partial auth found
4. No legacy SA relationships in DES
5. Returns 404

**Response**:
```
404 Not Found
"no partial-auth and no legacy SA relationship"
```

**Frontend Action**: Show message "No existing relationship found. Client needs to authorise agent." Direct client to invitation/authorisation flow to create new relationship from scratch.

### 4. Client NINO Not Registered for MTD

**Scenario**: Attempting to create relationship for client who hasn't registered for MTD

**Request**:
```
POST /itsa-post-signup/create-relationship/AB123456C
```

**Flow**:
1. Agent authenticates
2. System calls HIP to get MTDITID
3. HIP returns None (client not registered)
4. Returns 404 immediately

**Response**:
```
404 Not Found
"no MTDITID found for nino"
```

**Frontend Action**: Show "Client not registered for Making Tax Digital. Client must complete MTD-IT registration before relationship can be created."

## Error Handling

| Error | Response | Scenario | Note |
|-------|----------|----------|------|
| MTDITID not found | 404 | Client not registered for MTD or NINO invalid | Must complete MTD registration first |
| No partial auth | 404 | Partial auth doesn't exist when copy-across not attempted | Less common - usually tries copy-across next |
| No relationships | 404 | Neither partial auth nor legacy SA exists | Client needs to explicitly authorise agent |
| Relationship locked | 500 | Another process creating this relationship | Recovery lock in use - retry later |
| Creation failed | 500 | EACD or ETMP call failed | Check logs for specific failure |
| Unauthorized | 401 | Agent authentication failed | Invalid agent credentials |

## Important Notes

- ⚠️ **HIGH COMPLEXITY** - Two distinct flows (partial auth conversion vs legacy SA copy)
- ⚠️ **Partial auth takes precedence** over legacy SA copy (checked first)
- ⚠️ **Copy-across NOT eligible** if service is MTD-IT-SUPP (supplementary not migrated)
- ⚠️ **Uses recovery mechanism** - relationship-copy-record tracks progress for resilience
- ⚠️ **failIfAllocateAgentInESFails=true** for stricter EACD error handling
- ⚠️ **Cleans up duplicates** - removes same-agent duplicate relationships after creation
- ⚠️ **Returns 500 (not 423)** if relationship creation locked or failed
- ✅ Converts NINO to MTDITID at start via HIP (required for MTD-IT enrolment)
- ✅ Partial auth flow deletes partial auth and updates invitation to Accepted
- ✅ Legacy SA flow copies SA relationship from CESA/DES to MTD-IT
- ✅ Both flows create in EACD (enrolment) and ETMP (agent relationship)
- ✅ Returns service in response body (HMRC-MTD-IT or HMRC-MTD-IT-SUPP)
- ✅ Used during client MTD signup to establish agent relationship automatically
- ✅ Avoids need for client to re-authorise agent they already work with

## Related Documentation

- **ACR04**: Create Relationship - similar relationship creation but triggered differently
- **ACR15**: Accept Invitation - another way to create relationships (from client accepting invitation)
- **ACR16**: Create Relationship (complex) - handles multiple service types with various flows

