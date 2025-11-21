# ACR34: API Check Relationship

## Overview

External OAuth2 API endpoint that checks if an active agent-client relationship exists for a specific service. Similar to ACR01 but designed for external platforms, with known fact verification instead of user authentication. Uses full ACR01 checkForRelationship flow via CheckRelationshipsOrchestratorService. Validates agent not suspended, client registered, and known fact matches before checking relationship.

## API Details

- **API ID**: ACR34
- **Method**: POST
- **Path**: `/api/{arn}/relationship`
- **Authentication**: OAuth2 (external system authentication)
- **Audience**: external
- **Controller**: ApiCheckRelationshipController
- **Controller Method**: `checkRelationship`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `arn` | Arn | Yes | Agent Reference Number - relationship must belong to this agent |

## Query Parameters

None

## Request Body

The request body must be a JSON object with the following fields:

```json
{
  "service": "HMRC-MTD-VAT",
  "suppliedClientId": "101747696",
  "knownFact": "2007-05-18"
}
```

**Request Fields**:
- `service`: Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-MTD-TRUST, etc.)
- `suppliedClientId`: Client identifier appropriate for the service (NINO, VRN, UTR, URN, CGT reference, etc.)
- `knownFact`: Known fact for the client for verification (e.g., postcode for VAT, date of birth for IT, etc.)

## Response

### Success Response (204 No Content)

Empty body - relationship exists.

```
HTTP/1.1 204 No Content
```

### Error Responses

| Status | Code | Description |
|--------|------|-------------|
| 401 | Unauthorized | OAuth2 authentication failed |
| 403 | AGENT_SUSPENDED | Agent is suspended |
| 403 | KNOWN_FACT_DOES_NOT_MATCH | Known fact verification failed |
| 404 | CLIENT_REGISTRATION_NOT_FOUND | Client not registered for service |
| 404 | RELATIONSHIP_NOT_FOUND | No active relationship exists |
| 423 | CLIENT_INSOLVENT | Client has insolvent status |
| 500 | INTERNAL_SERVER_ERROR | Internal server error |

**Error Response Example**:
```json
{
  "code": "RELATIONSHIP_NOT_FOUND"
}
```

## Service Architecture

See ACR34.mmd for complete sequence diagram.

**Flow Summary**:
1. OAuth2 authentication check
2. Verify agent is not suspended via AgentAssuranceService
3. Retrieve client registration details via ClientDetailsService
4. Validate known fact matches client record
5. Check client is not insolvent
6. Perform full ACR01 relationship check via CheckRelationshipsOrchestratorService
7. Return 204 No Content if relationship exists, or appropriate error

## Business Logic

### Agent Suspension Check

First validation step. Calls `AgentAssuranceService.getNonSuspendedAgentRecord(arn)` to verify the agent is not suspended. Returns `AGENT_SUSPENDED` (403) if the agent is suspended.

### Client Registration Check

Verifies the client is registered/subscribed for the specified service. Calls `ClientDetailsService.findClientDetails(service, suppliedClientId)` which queries DES/IF for client subscription details. Returns `CLIENT_REGISTRATION_NOT_FOUND` (404) if client is not registered.

### Known Fact Verification

Security measure to verify the caller has knowledge of the client. Checks `clientDetails.containsKnownFact(knownFact)` - the known fact must exactly match the client record. Returns `KNOWN_FACT_DOES_NOT_MATCH` (403) if there's a mismatch.

Known facts vary by service:
- **VAT (HMRC-MTD-VAT)**: VAT registration date
- **Income Tax (HMRC-MTD-IT)**: Postcode
- **Trusts (HMRC-TERS-ORG/TERSNT-ORG)**: Postcode
- **CGT (HMRC-CGT-PD)**: Postcode or country code
- **PPT (HMRC-PPT-ORG)**: Registration date

### Insolvency Check

Checks if the client has an insolvent status which blocks relationship operations. Checks `clientDetails.status.nonEmpty` - returns `CLIENT_INSOLVENT` (423) if the client is insolvent.

### Relationship Check

Performs the full ACR01 relationship check flow via `CheckRelationshipsOrchestratorService.checkForRelationship`. This is the same comprehensive check used by ACR01, querying multiple sources:
- **EACD (Enrolment Store Proxy)**: Principal group ID, delegated group IDs, user enrolments
- **UGS (users-groups-search)**: Group users for user-level permission checks
- **Agent Permissions**: Client assignment to access groups
- **IF/HIP**: NINO to MtdItId conversion for MTD-IT services
- **DES**: SA agent references for legacy relationship checks
- **Agent Mapping**: ARN to legacy SA agent reference mapping
- **Agent FI Relationship**: PIR (Personal Income Record) relationship checks

Returns `RELATIONSHIP_NOT_FOUND` (404) if no active relationship exists.

## Dependencies

### External Services

| Service | Purpose | Method | Note |
|---------|---------|--------|------|
| **Agent Assurance** | Verify agent not suspended | `GET /agent-assurance/agent/{arn}` | Must not be suspended |
| **DES/IF** | Client registration details | Various per service | Known fact verification |
| **Multiple via CROS** | Full relationship check | See ACR01 | Same as ACR01 flow |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| **AgentAssuranceService** | `getNonSuspendedAgentRecord` | Agent suspension check |
| **ClientDetailsService** | `findClientDetails` | Client registration and known fact verification |
| **CheckRelationshipsOrchestratorService** | `checkForRelationship` | Full ACR01 relationship check |

### Database Collections

None directly - database queries handled by CheckRelationshipsOrchestratorService (see ACR01)

## Use Cases

### 1. External Platform Checks Relationship Before Operation

**Scenario**: External system needs to verify relationship exists before performing an operation

**Flow**:
1. External system authenticates with OAuth2
2. Makes POST request with ARN, service, client ID, and known fact
3. System verifies agent is not suspended
4. System retrieves client registration details
5. System validates known fact matches client record
6. System checks client is not insolvent
7. System performs full ACR01 relationship check via orchestrator
8. Returns 204 No Content if relationship exists

**Response**:
```
HTTP/1.1 204 No Content
```

**Frontend Action**: Proceed with operation that requires active relationship

### 2. Relationship Does Not Exist

**Scenario**: External system checks but no relationship exists

**Flow**:
1. External system authenticates with OAuth2
2. Makes POST request with ARN, service, client ID, and known fact
3. System validates agent, client registration, and known fact
4. System performs relationship check - no relationship found
5. Returns 404 with RELATIONSHIP_NOT_FOUND

**Response**:
```json
{
  "code": "RELATIONSHIP_NOT_FOUND"
}
```

**Frontend Action**: Display error message or prompt to create invitation

### 3. Known Fact Verification Fails

**Scenario**: External system provides incorrect known fact

**Flow**:
1. External system authenticates with OAuth2
2. Makes POST request with incorrect known fact
3. System validates agent and retrieves client details
4. System detects known fact mismatch
5. Returns 403 with KNOWN_FACT_DOES_NOT_MATCH

**Response**:
```json
{
  "code": "KNOWN_FACT_DOES_NOT_MATCH"
}
```

**Frontend Action**: Display error - user must provide correct known fact

### 4. Client is Insolvent

**Scenario**: Client has insolvent status

**Flow**:
1. External system authenticates with OAuth2
2. Makes POST request with valid known fact
3. System validates agent and retrieves client details
4. System detects client insolvent status
5. Returns 423 with CLIENT_INSOLVENT

**Response**:
```json
{
  "code": "CLIENT_INSOLVENT"
}
```

**Frontend Action**: Display error - operations blocked for insolvent clients

## Error Handling

| Scenario | Response | Message | Note |
|----------|----------|---------|------|
| Agent is suspended | 403 | AGENT_SUSPENDED | Prevents suspended agents from checking relationships |
| Client not registered | 404 | CLIENT_REGISTRATION_NOT_FOUND | Client must be registered/subscribed to the service |
| Known fact mismatch | 403 | KNOWN_FACT_DOES_NOT_MATCH | Security measure - known fact must match exactly |
| Client is insolvent | 423 | CLIENT_INSOLVENT | Insolvent clients blocked from relationship operations |
| No relationship found | 404 | RELATIONSHIP_NOT_FOUND | No active relationship exists |
| OAuth2 auth fails | 401 | Unauthorized | External system authentication required |

## Important Notes

- ✅ External OAuth2 API for third-party platforms
- ✅ Similar to ACR01 but uses known fact instead of user authentication
- ✅ Uses full CheckRelationshipsOrchestratorService.checkForRelationship flow (same as ACR01)
- ✅ Validates agent not suspended before any operations
- ✅ Requires client registration check before relationship check
- ✅ Known fact verification provides additional security
- ✅ Returns 204 No Content (empty body) on success
- ✅ Checks for insolvent client status (423 response)
- ✅ Fail-fast validation approach - validates agent, then client, then known fact, then relationship
- ⚠️ Does NOT use agent reference records or UIDs
- ⚠️ Known fact must exactly match client record (case-sensitive for some fields)
- ⚠️ Client must be registered/subscribed to the service
- ⚠️ Uses same multi-source relationship check as ACR01 (EACD, UGS, Agent Permissions, DES, etc.)
- ⚠️ Designed for agent-authorisation-api integration

## Related Documentation

- **ACR01**: Check For Relationship - internal endpoint with same relationship check logic
- **ACR31**: API Create Invitation - external API for creating invitations
- **ACR32**: API Get Invitation - external API for retrieving single invitation
- **ACR33**: API Get Invitations - external API for retrieving all invitations

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
