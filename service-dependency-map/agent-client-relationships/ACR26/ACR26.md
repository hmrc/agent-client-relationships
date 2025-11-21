# ACR26: Get Stride Client Relationships

## Overview

Allows a Stride user (HMRC internal staff) to retrieve active agent-client relationships for a specific client. This endpoint validates the service and client identifier, then queries HIP (ETMP) to find any active relationships. Special handling exists for ITSA services which require NINO-to-MtdItId conversion via IF. Used by HMRC staff to view which agents are authorized to act on behalf of a client.

## API Details

- **API ID**: ACR26
- **Method**: GET
- **Path**: `/relationships/service/{service}/client/{clientIdType}/{clientId}`
- **Authentication**: Stride authentication with specific roles (maintain_agent_relationships or maintain_agent_manually_assure)
- **Audience**: Internal (HMRC staff only)
- **Controller**: RelationshipsController
- **Controller Method**: `getRelationships`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| service | String | Yes | Service identifier (e.g., HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG, HMRC-CGT-PD, IR-SA, HMRC-CBC-ORG) |
| clientIdType | String | Yes | Client identifier type matching the service (e.g., 'ni' for NINO, 'vrn' for VRN, 'utr' for UTR, 'CGTPDRef' for CGT) |
| clientId | String | Yes | Client identifier value (must be valid format for the clientIdType) |

## Query Parameters

None

## Request Body

None

## Response

### Success Response (200 OK)

```json
{
  "arn": "AARN1234567",
  "dateTo": "9999-12-31",
  "dateFrom": "2024-01-15",
  "agentName": "ABC Accountants Ltd"
}
```

| Field | Type | Description |
|-------|------|-------------|
| arn | String | Agent Reference Number |
| dateTo | String | End date of relationship (active relationships have 9999-12-31) |
| dateFrom | String | Start date of relationship (optional) |
| agentName | String | Agent name (optional) |

### Error Responses

| Status Code | Description | Example Body |
|-------------|-------------|--------------|
| 400 | Bad Request - Invalid service, clientIdType, or clientId format | `"Unknown service INVALID-SERVICE"` |
| 401 | Unauthorized - Stride authentication failed | N/A |
| 403 | Forbidden - Stride user does not have required role | N/A |
| 404 | Not Found - No active relationship exists | No body |
| 422 | Unprocessable Entity - HIP returned error (normalized to 404) | No body |
| 500 | Internal Server Error | Error message |

## Service Architecture

See [ACR26.mmd](ACR26.mmd) for complete sequence diagram.

### Flow Summary

1. **Validation**: Validate service identifier, clientIdType, and clientId format
2. **CBC Special Handling**: For CBC service, query EACD to determine CBC-ORG vs CBC-NONUK-ORG and retrieve UTR
3. **Stride Authorization**: Check Stride authentication and roles
4. **Service Routing**:
   - **ITSA Services (MTD-IT, MTD-IT-SUPP)**: Convert NINO to MtdItId via IF, then query HIP
   - **Other Services**: Query HIP directly with tax identifier
5. **Active Filter**: Return only relationships with dateTo = 9999-12-31
6. **Response**: 200 with relationship JSON, or 404 if not found

## Business Logic

### Validation Process

The ValidationService performs multi-step validation:

1. **Special Cases**:
   - **IR-SA**: Accepts 'ni' or 'NINO' clientIdType, validates NINO format
   - **MTD-IT / MTD-IT-SUPP**: Accepts 'ni' or 'NINO', validates NINO format
   - **PIR**: Accepts 'NINO' clientIdType
   - **VAT (HMCE-VATDEC-ORG)**: Accepts 'vrn' clientIdType, validates VRN format
   - **CBC (HMRC-CBC-ORG)**: Queries EACD to determine if CBC-ORG or CBC-NONUK-ORG, retrieves UTR for CBC-ORG

2. **Normal Supported Services**: Validates service is in supported services list and clientId matches expected format

3. **Unsupported Services**: Returns 400 Bad Request with "Unknown service" message

### Service-Specific Routing

#### ITSA Services (MTD-IT, MTD-IT-SUPP)

Flow:
1. Call `FindRelationshipsService.getItsaRelationshipForClient(nino, service)`
2. Convert NINO to MtdItId via IF connector (`getMtdIdFor`)
3. If MtdItId found, query HIP with MtdItId
4. If MtdItId not found, return 404

#### Other Services

Flow:
1. Call `FindRelationshipsService.getActiveRelationshipsForClient(taxIdentifier, service)`
2. Check if identifier type is supported
3. If supported, query HIP directly with tax identifier
4. If unsupported, log warning and return 404

### Active Relationship Filtering

HIP returns all relationships, but this endpoint filters to find only active ones:
- **Active**: dateTo = "9999-12-31"
- **Inactive**: dateTo != "9999-12-31" (relationship has been terminated)

Only active relationships are returned. Inactive relationships result in 404.

### Stride Authorization

Requires Stride authentication with one of these roles:
- `maintain_agent_relationships`
- `maintain_agent_manually_assure`

### HIP Error Handling

Errors from HIP are normalized:
- **400 Bad Request**: Treated as no relationship (404)
- **404 Not Found**: Treated as no relationship (404)
- **422 Unprocessable Entity with "suspended"**: Treated as no relationship (404)
- **422 Unprocessable Entity with "009"**: Treated as no relationship (404)
- **Other errors**: Logged and treated as no relationship (404)

## Dependencies

### External Services

| Service | Method | Purpose | Note |
|---------|--------|---------|------|
| EACD (Enrolment Store Proxy) | queryKnownFacts | Determine CBC-ORG vs CBC-NONUK-ORG and retrieve UTR | Only for CBC service |
| IF (Integration Framework) | getMtdIdFor | Convert NINO to MtdItId | Only for MTD-IT and MTD-IT-SUPP |
| HIP (ETMP) | GET /registration/relationship | Retrieve active relationships | Uses auth profile based on service |

### Internal Services

| Service | Method | Purpose |
|---------|--------|---------|
| ValidationService | validateForEnrolmentKey | Validate service, clientIdType, and clientId |
| FindRelationshipsService | getItsaRelationshipForClient | Handle ITSA-specific flow with NINO-to-MtdItId conversion |
| FindRelationshipsService | getActiveRelationshipsForClient | Handle non-ITSA services |
| IFConnector | getMtdIdFor | Convert NINO to MtdItId for ITSA |
| HIPConnector | getActiveClientRelationships | Query HIP for active relationships |

### Database Collections

None - This endpoint queries HIP (ETMP) directly, not local MongoDB

## Use Cases

### 1. HMRC Staff Checks MTD-IT Relationships

**Scenario**: Stride user needs to see which agents are authorized for a client's MTD-IT service

**Flow**:
1. Stride user authenticated with required role
2. Calls endpoint with service=HMRC-MTD-IT, clientIdType=ni, clientId={NINO}
3. Validation passes (NINO is valid format)
4. NINO converted to MtdItId via IF
5. HIP queried with MtdItId
6. Active relationship found
7. 200 OK returned with relationship details

**Response**:
```json
{
  "arn": "AARN1234567",
  "dateTo": "9999-12-31",
  "dateFrom": "2024-01-15",
  "agentName": "ABC Accountants Ltd"
}
```

**Frontend Action**: Display agent details to Stride user

### 2. Check VAT Relationships

**Scenario**: Stride user checks VAT relationships for a client

**Flow**:
1. Stride user calls with service=HMRC-MTD-VAT, clientIdType=vrn, clientId={VRN}
2. Validation passes (VRN is valid format)
3. HIP queried directly with VRN (no conversion needed)
4. Active relationship found
5. 200 OK returned

**Response**:
```json
{
  "arn": "AARN7654321",
  "dateTo": "9999-12-31"
}
```

**Frontend Action**: Display agent details

### 3. No Relationship Exists

**Scenario**: Client has no active relationships

**Flow**:
1. Stride user calls endpoint
2. Validation and authorization succeed
3. HIP queried
4. No active relationship found (either none exist or all are inactive)
5. 404 Not Found returned

**Response**: 404 Not Found (no body)

**Frontend Action**: Show "No active relationships found" message

### 4. Invalid Service Identifier

**Scenario**: Stride user provides unsupported service

**Flow**:
1. Stride user calls with unsupported service identifier
2. Validation fails
3. 400 Bad Request returned

**Response**: 
```
400 Bad Request
"Unknown service INVALID-SERVICE"
```

**Frontend Action**: Show validation error message

### 5. CBC Service - UK Version

**Scenario**: Check CBC relationships for UK CBC client

**Flow**:
1. Stride user calls with service=HMRC-CBC-ORG, clientIdType=cbcId, clientId={cbcId}
2. ValidationService queries EACD for HMRC-CBC-ORG with cbcId
3. UTR found in EACD (UK version)
4. EnrolmentKey created with both UTR and cbcId
5. HIP queried with correct identifiers
6. Relationship returned

**Response**:
```json
{
  "arn": "AARN9999999",
  "dateTo": "9999-12-31"
}
```

**Frontend Action**: Display agent details

### 6. CBC Service - Non-UK Version

**Scenario**: Check CBC relationships for non-UK CBC client

**Flow**:
1. Stride user calls with service=HMRC-CBC-ORG, clientIdType=cbcId, clientId={cbcId}
2. ValidationService queries EACD for HMRC-CBC-ORG - not found
3. ValidationService queries EACD for HMRC-CBC-NONUK-ORG - found
4. EnrolmentKey created for CBC-NONUK-ORG
5. HIP queried
6. Relationship returned

**Response**:
```json
{
  "arn": "AARN8888888",
  "dateTo": "9999-12-31"
}
```

**Frontend Action**: Display agent details

### 7. ITSA Client with No MtdItId

**Scenario**: NINO exists but not enrolled for MTD-IT (no MtdItId)

**Flow**:
1. Stride user calls with MTD-IT service and NINO
2. Validation passes
3. IF connector called to get MtdItId
4. No MtdItId found
5. 404 returned

**Response**: 404 Not Found (no body)

**Frontend Action**: Show "Client not enrolled for MTD-IT" or similar message

## Error Handling

| Error Scenario | Response | Message | Note |
|----------------|----------|---------|------|
| Unknown or unsupported service | 400 Bad Request | Unknown service {service} | Service must be in supported list |
| Invalid clientId format | 400 Bad Request | Validation error details | Format must match service requirements |
| Stride authentication failure | 401 Unauthorized | Authentication failed | Must be Stride authenticated |
| Insufficient Stride permissions | 403 Forbidden | Forbidden | Must have required Stride role |
| No active relationship | 404 Not Found | No body | Either doesn't exist or is inactive |
| NINO has no MtdItId (ITSA) | 404 Not Found | No body | Client not enrolled for MTD-IT |
| HIP returns suspended agent | 404 Not Found | No body | 422 from HIP treated as no relationship |
| HIP returns 400 or 404 | 404 Not Found | No body | Normalized to 404 |
| HIP returns other error | 404 Not Found | No body (error logged) | Unexpected errors logged server-side |
| Unsupported identifier type | 404 Not Found | No body (warning logged) | Identifier type not in supported list |

## Important Notes

- ✅ Stride-only endpoint - not accessible to agents or clients
- ✅ Only returns ACTIVE relationships (dateTo = 9999-12-31)
- ✅ Different flow for ITSA services (MTD-IT, MTD-IT-SUPP) - requires NINO-to-MtdItId conversion
- ✅ CBC service has special handling - queries EACD to determine CBC-ORG vs CBC-NONUK-ORG
- ✅ Validation occurs before authentication (efficient rejection of invalid requests)
- ✅ Queries HIP (ETMP) for relationship data - does NOT use local MongoDB
- ✅ Returns single relationship (the active one) - not all relationships
- ⚠️ Returns 404 for inactive relationships (dateTo != 9999-12-31) - not just missing ones
- ⚠️ For ITSA: If NINO has no MtdItId in IF, returns 404
- ⚠️ HIP errors (including suspended agent) are normalized to 404 - actual errors logged server-side
- ⚠️ Unsupported identifier types return 404 with warning log - not 400
- ⚠️ Requires specific Stride roles: maintain_agent_relationships or maintain_agent_manually_assure
- ⚠️ Uses auth profile when querying HIP - different profiles for different services
- ⚠️ Does NOT filter by ARN - returns any active relationship for the client/service combination

## Related Documentation

- **ACR01**: Check for Relationship - Agent/client version of relationship checking
- **ACR02**: Get Active Client Relationships - Agent queries their own relationships
- **ACR09**: Get Client Relationship Details - More comprehensive relationship data
  - `relationships`: Active relationship records

## Notes

- Restricted to Stride-authenticated HMRC staff
- Used for customer support and compliance purposes
- Returns only active relationships for the specified service

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
