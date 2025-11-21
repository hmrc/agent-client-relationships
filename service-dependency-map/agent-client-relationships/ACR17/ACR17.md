# ACR17: Create Authorization Request (Invitation)

## Overview

Allows an agent to create a new authorization request (invitation) for a client. The endpoint validates the request, retrieves agent details, optionally converts NINO to MTD IT ID for MTD-IT services, creates an invitation record in MongoDB with Pending status, and returns the invitationId.

**Note**: This endpoint does NOT send email to the client - the client accesses the invitation via link (ACR10) or the agent provides the invitation ID.

## API Details

- **API ID**: ACR17
- **Method**: POST
- **Path**: `/agent/{arn}/authorisation-request`
- **Authentication**: Basic `authorised()` check (no agent-specific validation)
- **Audience**: internal
- **Controller**: InvitationController
- **Controller Method**: `createInvitation`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| arn | Arn | Yes | Agent Reference Number (e.g., TARN0000001) |

## Request Body

```json
{
  "service": "HMRC-MTD-IT",
  "clientIdType": "ni",
  "clientId": "AB123456C",
  "clientName": "John Smith",
  "clientType": "personal"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| service | String | Yes | Service identifier (HMRC-MTD-IT, HMRC-MTD-VAT, etc.) |
| clientIdType | String | Yes | Type of client ID (ni, vrn, utr, urn, CGTPDRef, etc.) |
| clientId | String | Yes | Client identifier value |
| clientName | String | Yes | Client's name for display |
| clientType | String | No | "personal" or "business" |

## Response

### Success Response (201 Created)

```json
{
  "invitationId": "ABBBBBBBBBBBB"
}
```

### Error Responses

- **400 Bad Request**: Invalid payload, unsupported service/clientIdType/clientType
- **401 Unauthorized**: Not authenticated
- **403 Forbidden**: Duplicate invitation exists
- **404 Not Found**: Client registration not found (MTD IT ID lookup failed)

## Service Architecture

See ACR17.mmd for complete sequence diagram.

### Process Flow

1. **Validate Payload**: JSON structure and field values
2. **Get Agent Details**: Retrieve agency name and email from agent-assurance
3. **Convert Client ID** (MTD-IT/MTD-IT-SUPP with NINO only):
   - Call IF/HIP getMtdIdFor(nino)
   - If found: use MTD IT ID as clientId
   - If not found: use NINO as clientId (for alt-itsa)
4. **Calculate Expiry**: now + invitationExpiringDuration (typically 21 days)
5. **Create Invitation**: Insert into MongoDB with Pending status
6. **Audit Event**: Record invitation creation
7. **Return**: 201 Created with invitationId

## Dependencies

### External Services

- **agent-assurance**: Provides agent details (agency name, email)
  - Uses getAgentRecord - works even if agent suspended
- **IF/HIP**: Converts NINO to MTD IT ID (MTD-IT/MTD-IT-SUPP only)

### Database Collections

- **invitations**: INSERT new invitation with:
  - invitationId (generated)
  - arn, service, clientId, suppliedClientId
  - status: Pending
  - created, lastUpdated, expiryDate
  - agentName, agencyEmail (from agent-assurance)
  - clientName, clientType (from request)
  - Unique index: (arn, service, clientId, status)

## Business Logic

### Client ID Conversion (MTD-IT/MTD-IT-SUPP)

For MTD-IT and MTD-IT-SUPP services with NINO:

1. Call IF/HIP `getMtdIdFor(nino)`
2. **If MTD IT ID found**:
   - Store MTD IT ID as `clientId`
   - Store NINO as `suppliedClientId`
3. **If not found**:
   - Store NINO as `clientId` (for alt-itsa)
   - Store NINO as `suppliedClientId`

**Other services**: Use supplied clientId as-is

### Expiry Date Calculation

```
expiryDate = now + invitationExpiringDuration
```

- Duration configured in app config (typically 21 days)
- Stored as LocalDate

### Duplicate Prevention

MongoDB unique index on `(arn, service, clientId, status)` prevents:
- Multiple Pending invitations for same agent/service/client

If duplicate attempted:
- MongoDB throws MongoException with "E11000 duplicate key error"
- Returns 403 Forbidden (DuplicateInvitationError)

### No Email Sent

⚠️ **Important**: This endpoint does NOT send email to client

**Client access methods**:
1. Agent shares invitation link (generated via ACR11)
2. Agent provides invitation ID
3. Client logs in and sees pending invitations

## Use Cases

### 1. Agent Creates MTD-IT Invitation

**Request**:
```json
{
  "service": "HMRC-MTD-IT",
  "clientIdType": "ni",
  "clientId": "AB123456C",
  "clientName": "John Smith",
  "clientType": "personal"
}
```

**Flow**:
1. Validates request
2. Gets agent details from agent-assurance
3. Converts NINO to MTD IT ID via IF/HIP
4. Creates invitation
5. Returns invitationId

**Response**: 201 Created with `{"invitationId": "ABBBBBBBBBBBB"}`

### 2. Agent Creates VAT Invitation

**Request**:
```json
{
  "service": "HMRC-MTD-VAT",
  "clientIdType": "vrn",
  "clientId": "123456789",
  "clientName": "ABC Ltd",
  "clientType": "business"
}
```

**Flow**:
1. Validates request
2. Gets agent details
3. Uses VRN as-is (no conversion)
4. Creates invitation

**Response**: 201 Created with `{"invitationId": "CCCCCCCCCCCCC"}`

### 3. Duplicate Invitation Attempt

**Scenario**: Agent already has Pending invitation for this client/service

**Response**: 403 Forbidden (DuplicateInvitationError)

**Message**: "An authorisation request for this service has already been created and is awaiting the client's response."

### 4. Unsupported Service

**Request**:
```json
{
  "service": "INVALID-SERVICE",
  "clientIdType": "ni",
  "clientId": "AB123456C"
}
```

**Response**: 400 Bad Request (UnsupportedService)

## Error Handling

| Error | Response | Message |
|-------|----------|---------|
| Invalid JSON | 400 Bad Request | "Invalid payload: {errors}" |
| Unsupported service | 400 Bad Request | "Unsupported service \"{service}\"" |
| Invalid clientId | 400 Bad Request | "Invalid clientId \"{clientId}\", for service type \"{service}\"" |
| Unsupported clientIdType | 400 Bad Request | "Unsupported clientIdType \"{clientIdType}\", for service type \"{service}\"" |
| Unsupported clientType | 400 Bad Request | "Unsupported clientType \"{clientType}\"" |
| Duplicate invitation | 403 Forbidden | "An authorisation request for this service has already been created..." |
| Client registration not found | 404 Not Found | "The Client's MTDfB registration or SAUTR (if alt-itsa is enabled) was not found." |
| Not authenticated | 401 Unauthorized | - |

## Supported Services

- HMRC-MTD-IT
- HMRC-MTD-IT-SUPP
- HMRC-MTD-VAT
- HMRC-TERS-ORG
- HMRC-TERSNT-ORG
- HMRC-CGT-PD
- HMRC-PPT-ORG
- HMRC-CBC-ORG
- HMRC-PILLAR2-ORG

## Supported Client ID Types

- ni (NINO)
- vrn (VAT Registration Number)
- utr (Unique Taxpayer Reference)
- urn (Unique Reference Number)
- CGTPDRef (CGT Reference)
- PPTRef (PPT Reference)
- cbcId (CBC ID)
- plrId (Pillar2 ID)

## Important Notes

- ✅ **Creates Pending Invitation**: Stored in MongoDB
- ✅ **MTD-IT Conversion**: NINO → MTD IT ID conversion attempted
- ✅ **Expiry Date**: Calculated from config (typically 21 days)
- ✅ **Agent Details**: Retrieved from agent-assurance
- ✅ **Duplicate Prevention**: MongoDB unique index
- ✅ **Audit Event**: Invitation creation audited
- ⚠️ **NO EMAIL SENT**: Client accesses via link or invitation ID
- ⚠️ **Basic Auth**: No validation ARN matches authenticated agent
- ⚠️ **Works When Suspended**: Uses getAgentRecord (not getNonSuspendedAgentRecord)
- ⚠️ **Alt-ITSA Support**: If MTD IT ID not found, uses NINO

## Related Documentation

- **ACR10**: Validate Agent Invitation Link (client validates link)
- **ACR11**: Create Agent Link (agent gets shareable link)
- **ACR12**: Validate Invitation for Client (client validates before accept/reject)
- **ACR15**: Reject Invitation (client rejects)
- **ACR16**: Accept Invitation (client accepts)

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
