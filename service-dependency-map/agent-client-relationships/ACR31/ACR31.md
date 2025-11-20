# ACR31: API Create Invitation

## Overview

⚠️ **VERY HIGH COMPLEXITY** - Allows an external authenticated system (OAuth2) to create an agent authorization invitation on behalf of an agent. Performs extensive validation including: payload validation, agent suspension check, client registration check, known facts verification, duplicate invitation check, existing relationship check (including PartialAuth for ITSA). For MTD-IT/MTD-IT-SUPP with NINO, converts to MtdItId via IF. Creates invitation record in MongoDB with expiry date. Used by third-party platforms integrating with HMRC agent services.

## API Details

- **API ID**: ACR31
- **Method**: POST
- **Path**: `/api/{arn}/invitation`
- **Authentication**: OAuth2 with appropriate scopes for creating invitations
- **Audience**: External (Third-party platforms)
- **Controller**: ApiCreateInvitationController
- **Controller Method**: `createInvitation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | String | Yes | Agent Reference Number (must be valid ARN format, e.g., AARN1234567) |

## Request Body

```json
{
  "service": "HMRC-MTD-IT",
  "suppliedClientId": "AB123456C",
  "knownFact": "AA11AA",
  "clientType": "personal"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| service | String | Yes | Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG) - must be in API supported services list |
| suppliedClientId | String | Yes | Client identifier value (e.g., NINO for MTD-IT, VRN for VAT) - must match service type and be valid format |
| knownFact | String | Yes | Known fact for verification (e.g., postcode for MTD-IT, VAT registration date for VAT) |
| clientType | String | No | Client type: 'personal', 'business', or 'trust' |

## Response

### Success Response (201 Created)

```json
{
  "invitationId": "ABCD1234567890EFGH"
}
```

| Field | Type | Description |
|-------|------|-------------|
| invitationId | String | Unique invitation ID (UUID format) |

### Error Responses

| Status Code | Error Code | Description |
|-------------|------------|-------------|
| 400 | INVALID_PAYLOAD | Request body doesn't match expected schema |
| 401 | N/A | OAuth2 authentication failed |
| 403 | AGENT_SUSPENDED | Agent's account is suspended |
| 403 | POSTCODE_FORMAT_INVALID | Postcode format is invalid (MTD-IT) |
| 403 | POSTCODE_DOES_NOT_MATCH | Postcode doesn't match HMRC records (MTD-IT) |
| 403 | VAT_REG_DATE_FORMAT_INVALID | VAT registration date format is invalid |
| 403 | VAT_REG_DATE_DOES_NOT_MATCH | VAT registration date doesn't match HMRC records |
| 422 | UNSUPPORTED_SERVICE | Service not in API supported services list |
| 422 | CLIENT_ID_INVALID_FORMAT | Client ID format doesn't match service requirements |
| 422 | CLIENT_ID_DOES_NOT_MATCH_SERVICE | Client ID type doesn't match service |
| 422 | UNSUPPORTED_CLIENT_TYPE | Client type not valid (must be personal/business/trust) |
| 422 | CLIENT_REGISTRATION_NOT_FOUND | Client not registered for this service in DES |
| 422 | VAT_CLIENT_INSOLVENT | VAT client has insolvency status |
| 422 | DUPLICATE_AUTHORISATION_REQUEST | Pending invitation already exists (includes invitationId) |
| 422 | ALREADY_AUTHORISED | Active relationship already exists |
| 500 | N/A | Internal server error |

## Service Architecture

See [ACR31.mmd](ACR31.mmd) for complete sequence diagram.

### Flow Summary

1. **OAuth2 Authentication**: External system must be authenticated
2. **Payload Validation**: Validate JSON structure, service, clientId format, clientType
3. **Client ID Conversion** (MTD-IT/MTD-IT-SUPP only): Convert NINO to MtdItId via IF (or use NINO if not found)
4. **Duplicate Invitation Check**: Check for existing pending invitations (including MTD-IT/MTD-IT-SUPP cross-check)
5. **Agent Suspension Check**: Verify agent not suspended via Agent Maintainer
6. **Client Registration Check**: Verify client registered for service in DES
7. **Known Facts Verification**: Verify supplied known fact matches client details
8. **Existing Relationship Check**: Check HIP and PartialAuth (ITSA only) for existing relationships
9. **Invitation Creation**: Create invitation in MongoDB with expiry date
10. **Audit Event**: Send audit event on success
11. **Response**: 201 Created with invitationId

## Business Logic

### Payload Validation

Multi-step validation of request body:
- **Service**: Must be in API supported services list
- **SuppliedClientId**: Format must match service requirements (e.g., valid NINO for MTD-IT)
- **ClientType**: If provided, must be one of: personal, business, trust

### MTD-IT ID Conversion

For MTD-IT and MTD-IT-SUPP services with NINO:
1. Call IF `getMtdIdFor(nino)`
2. If MtdItId found: Use MtdItId as clientId
3. If not found: Use NINO as clientId
4. This allows invitations for clients not yet enrolled in MTD-IT

### Duplicate Invitation Check

Checks for existing pending invitations:
- Queries invitations collection for same agent, service, and client
- For MTD-IT: Also checks MTD-IT-SUPP
- For MTD-IT-SUPP: Also checks MTD-IT
- Cannot have both MTD-IT and MTD-IT-SUPP pending simultaneously
- Returns `DUPLICATE_AUTHORISATION_REQUEST` with existing invitationId if found

### Agent Suspension Check

Verifies agent is not suspended:
- Calls `AgentAssuranceService.getNonSuspendedAgentRecord(arn)`
- Retrieves agent record from Agent Maintainer
- Returns `AGENT_SUSPENDED` (403) if suspended
- Also retrieves agency name and email for invitation

### Client Registration Check

Verifies client is registered for the service:
- Calls `ClientDetailsService.findClientDetails(service, clientId)`
- Queries appropriate DES endpoint based on service
- Returns `CLIENT_REGISTRATION_NOT_FOUND` (422) if not found
- For VAT: Also checks insolvency status
- Returns `VAT_CLIENT_INSOLVENT` (422) if insolvent

### Known Facts Verification

Verifies supplied known fact matches client details:
- `ApiKnownFactsCheckService.checkKnownFacts(knownFact, clientDetails)`
- **MTD-IT**: Validates postcode format and match
- **VAT**: Validates VAT registration date format and match
- Returns appropriate 403 error if mismatch

### Existing Relationship Check

Checks if relationship already exists:
1. `CheckRelationshipsOrchestratorService.checkForRelationship()` queries HIP/ETMP
2. For ITSA: Also checks `PartialAuthRepository.findActive()`
3. Returns `ALREADY_AUTHORISED` (422) if relationship exists

### Invitation Creation

Creates invitation record in MongoDB:
- Calculates expiry date (current time + configured duration)
- Creates invitation with status=Pending
- Includes: arn, service, clientId, suppliedClientId, clientName, agencyName, agencyEmail, expiryDate, clientType
- Handles race condition with duplicate key error from MongoDB
- Sends audit event on success

### OAuth2 Authorization

External system must be authenticated via OAuth2 with appropriate scopes for creating invitations.

## Dependencies

### External Services

| Service | Method | Purpose | Note |
|---------|--------|---------|------|
| IF (Integration Framework) | getMtdIdFor | Convert NINO to MtdItId | Only for MTD-IT/MTD-IT-SUPP. If no MtdItId, uses NINO |
| Agent Maintainer | GET /agent-maintainer/agent/{arn} | Retrieve agent record and suspension status | Returns 403 if suspended |
| DES (Multiple endpoints) | Various | Retrieve client details | Different endpoint per service. VAT includes insolvency check |
| HIP (ETMP) | GET /registration/relationship | Check for existing relationship | Via CheckRelationshipsOrchestratorService |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| ValidationService | Inline methods | Validate payload structure and values |
| IfOrHipConnector | getMtdIdFor | NINO to MtdItId conversion |
| InvitationsRepository | findAllForAgent, create | Check for duplicates, create invitation |
| AgentAssuranceService | getNonSuspendedAgentRecord | Get agent details, filter suspended |
| ClientDetailsService | findClientDetails | Retrieve client details from DES |
| ApiKnownFactsCheckService | checkKnownFacts | Verify known facts |
| CheckRelationshipsOrchestratorService | checkForRelationship | Check HIP for existing relationship |
| PartialAuthRepository | findActive | Check PartialAuth for ITSA |

### Database Collections

| Collection | Operation | Description |
|------------|-----------|-------------|
| invitations | READ, WRITE | Check for duplicates, create new invitation |
| partial-auth | READ | For ITSA: check existing PartialAuth relationships |

## Use Cases

### 1. External Platform Creates MTD-IT Invitation

**Scenario**: Third-party software creates invitation for MTD-IT client

**Flow**:
1. External system authenticates via OAuth2
2. Submits POST with service=HMRC-MTD-IT, suppliedClientId=NINO, knownFact=postcode
3. Payload validation passes
4. NINO converted to MtdItId via IF (or kept as NINO if no MtdItId)
5. No pending invitations exist
6. Agent not suspended
7. Client found in DES with matching postcode
8. No existing relationship in HIP
9. No existing PartialAuth
10. Invitation created with expiry date
11. 201 Created with invitationId

**Response**:
```json
{
  "invitationId": "ABCD1234567890EFGH"
}
```

**Frontend Action**: Store invitationId, display success message to agent

### 2. Duplicate Invitation Attempt

**Scenario**: External system tries to create invitation when one already pending

**Flow**:
1. External system submits invitation request
2. Validation passes
3. Pending invitation already exists for same agent, service, client
4. 422 Unprocessable Entity returned with existing invitationId

**Response**:
```json
{
  "code": "DUPLICATE_AUTHORISATION_REQUEST",
  "message": "An authorisation request for this service has already been created and is awaiting the client's response.",
  "invitationId": "EXISTING1234567890"
}
```

**Frontend Action**: Show "Invitation already pending" with link to existing invitation

### 3. Agent Suspended

**Scenario**: Agent's account is suspended

**Flow**:
1. External system submits request
2. Payload validation passes
3. Agent Maintainer returns suspended status
4. 403 Forbidden returned

**Response**:
```json
{
  "code": "AGENT_SUSPENDED",
  "message": "The agent's account is suspended."
}
```

**Frontend Action**: Show "Agent account suspended" error

### 4. Known Fact Mismatch (Postcode)

**Scenario**: Supplied postcode doesn't match HMRC records

**Flow**:
1. External system submits MTD-IT invitation
2. Validations pass, client found
3. Supplied postcode doesn't match client's postcode in DES
4. 403 Forbidden returned

**Response**:
```json
{
  "code": "POSTCODE_DOES_NOT_MATCH",
  "message": "The postcode provided does not match HMRC's record for the client."
}
```

**Frontend Action**: Show "Postcode mismatch" error, ask agent to verify

### 5. Relationship Already Exists

**Scenario**: Active relationship already exists in HIP

**Flow**:
1. External system submits request
2. All validations pass
3. HIP check finds existing active relationship
4. 422 Unprocessable Entity returned

**Response**:
```json
{
  "code": "ALREADY_AUTHORISED",
  "message": "An authorisation already exists for this agent and client."
}
```

**Frontend Action**: Show "Already authorised" message

### 6. VAT Client Insolvent

**Scenario**: VAT client has insolvency status

**Flow**:
1. External system submits VAT invitation
2. Validations pass
3. DES returns VAT client with insolvency status
4. 422 Unprocessable Entity returned

**Response**:
```json
{
  "code": "VAT_CLIENT_INSOLVENT",
  "message": "The VAT client is insolvent."
}
```

**Frontend Action**: Show "Client insolvent, cannot create invitation"

### 7. Client Not Registered

**Scenario**: Client not found in DES for service

**Flow**:
1. External system submits request
2. Validations pass
3. DES returns 404 for client
4. 422 Unprocessable Entity returned

**Response**:
```json
{
  "code": "CLIENT_REGISTRATION_NOT_FOUND",
  "message": "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found."
}
```

**Frontend Action**: Show "Client not registered for this service"

### 8. MTD-IT Invitation When MTD-IT-SUPP Pending

**Scenario**: Can't have both MTD-IT and MTD-IT-SUPP pending simultaneously

**Flow**:
1. External system requests MTD-IT invitation
2. Validation passes
3. Pending MTD-IT-SUPP invitation exists for same agent and client
4. 422 Unprocessable Entity returned

**Response**:
```json
{
  "code": "DUPLICATE_AUTHORISATION_REQUEST",
  "message": "An authorisation request for this service has already been created and is awaiting the client's response.",
  "invitationId": "SUPP1234567890ABCD"
}
```

**Frontend Action**: Show "Related MTD-IT-SUPP invitation pending"

## Error Handling

| Error Scenario | Response | Code | Note |
|----------------|----------|------|------|
| Invalid JSON payload | 400 Bad Request | INVALID_PAYLOAD | Malformed JSON or missing required fields |
| Unsupported service | 422 Unprocessable Entity | UNSUPPORTED_SERVICE | Service not in API supported list |
| Invalid client ID format | 422 Unprocessable Entity | CLIENT_ID_INVALID_FORMAT | Format doesn't match service |
| Client ID doesn't match service | 422 Unprocessable Entity | CLIENT_ID_DOES_NOT_MATCH_SERVICE | Type mismatch |
| Unsupported client type | 422 Unprocessable Entity | UNSUPPORTED_CLIENT_TYPE | Not personal/business/trust |
| OAuth2 auth failure | 401 Unauthorized | N/A | Not authenticated |
| Agent suspended | 403 Forbidden | AGENT_SUSPENDED | Agent Maintainer suspension status |
| Client not registered | 422 Unprocessable Entity | CLIENT_REGISTRATION_NOT_FOUND | Not in DES |
| VAT client insolvent | 422 Unprocessable Entity | VAT_CLIENT_INSOLVENT | DES insolvency status |
| Invalid postcode format | 403 Forbidden | POSTCODE_FORMAT_INVALID | MTD-IT postcode invalid |
| Postcode mismatch | 403 Forbidden | POSTCODE_DOES_NOT_MATCH | Doesn't match DES |
| Invalid VAT reg date format | 403 Forbidden | VAT_REG_DATE_FORMAT_INVALID | Format invalid |
| VAT reg date mismatch | 403 Forbidden | VAT_REG_DATE_DOES_NOT_MATCH | Doesn't match DES |
| Duplicate invitation | 422 Unprocessable Entity | DUPLICATE_AUTHORISATION_REQUEST | Pending invitation exists |
| Relationship exists | 422 Unprocessable Entity | ALREADY_AUTHORISED | HIP or PartialAuth |
| MongoDB race condition | 422 Unprocessable Entity | DUPLICATE_AUTHORISATION_REQUEST | Concurrent create |
| Internal error | 500 Internal Server Error | N/A | Unexpected failures |

## Important Notes

- ✅ VERY HIGH COMPLEXITY - extensive validation pipeline with multiple external dependencies
- ✅ External API endpoint - OAuth2 authentication required
- ✅ Used by third-party platforms integrating with HMRC
- ✅ For MTD-IT/MTD-IT-SUPP: converts NINO to MtdItId via IF (or uses NINO if no MtdItId)
- ✅ Cross-checks MTD-IT and MTD-IT-SUPP for duplicate invitations
- ✅ Returns existing invitationId in DUPLICATE_AUTHORISATION_REQUEST error
- ✅ Creates invitation with calculated expiry date
- ✅ Sends audit event on successful creation
- ✅ Validates known facts (postcode for MTD-IT, VAT reg date for VAT)
- ✅ Checks both HIP and PartialAuth for existing ITSA relationships
- ⚠️ Agent suspension check returns 403 (not 422)
- ⚠️ Known fact mismatch returns 403 (not 422)
- ⚠️ Business rule violations return 422 Unprocessable Entity
- ⚠️ Duplicate key error from MongoDB handled as race condition
- ⚠️ VAT clients: checks insolvency status
- ⚠️ MTD-IT without MtdItId: uses NINO as clientId (allows pre-enrollment invitations)
- ⚠️ No relationship is created at this point - only invitation
- ⚠️ Client must accept invitation for relationship to be created
- ⚠️ Performance: Multiple sequential validations and external calls
- ⚠️ Each validation failure terminates early (fail-fast approach)

## Related Documentation

- **ACR32**: API Get Invitation - Retrieve invitation by ID
- **ACR33**: API Get Invitations - List invitations for agent
- **ACR14**: Agent Create Invitation - Internal agent-facing version
- **ACR16**: Accept Invitation - Client accepts invitation to create relationship

