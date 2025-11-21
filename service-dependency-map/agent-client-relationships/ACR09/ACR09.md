# ACR09: Get Client Details for Agent

## Overview

Retrieves comprehensive client details for the authenticated agent, enriched with invitation and relationship status. This endpoint is used during agent invitation and relationship management flows to:
- Verify client existence and display their name
- Show known facts (postcode, date of birth, etc.) for client verification
- Check if agent has already sent an invitation to this client
- Check if agent already has an existing relationship with this client

The endpoint queries service-specific external APIs for client information, then enriches the response with internal status checks.

## API Details

- **API ID**: ACR09
- **Method**: GET
- **Path**: `/client/{service}/details/{clientId}`
- **Authentication**: Agent authentication via `withAuthorisedAsAgent`
- **Audience**: internal
- **Controller**: ClientDetailsController
- **Controller Method**: `findClientDetails`

## Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| service | String | Yes | Service identifier (HMRC-MTD-IT, HMRC-MTD-VAT, HMRC-TERS-ORG, etc.) |
| clientId | String | Yes | Client identifier - format varies by service (NINO, VRN, UTR, etc.) |

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns ClientDetailsResponse with client information and status flags:

```json
{
  "name": "John Smith",
  "status": null,
  "isOverseas": false,
  "knownFacts": ["SW1A1AA"],
  "knownFactType": "PostalCode",
  "hasPendingInvitation": false,
  "hasExistingRelationshipFor": "HMRC-MTD-VAT"
}
```

**Field Descriptions**:
- `name`: Client name (individual or organization)
- `status`: Optional client status
- `isOverseas`: Whether client is overseas (varies by service)
- `knownFacts`: Array of known facts for verification (postcode, date of birth, email, etc.)
- `knownFactType`: Type of known fact (PostalCode, DateOfBirth, VatRegistrationDate, Email, PlrReference, Overseas)
- `hasPendingInvitation`: true if agent has pending invitation for this client (checks main AND supporting services)
- `hasExistingRelationshipFor`: Service ID if relationship exists, null otherwise (checks main AND supporting services)

### Error Responses

- **404 Not Found**: Client not found in external service
- **401 Unauthorized**: Agent not authenticated
- **500 Internal Server Error**: Error retrieving client details or unexpected error during checks

## Service Architecture

See ACR09.mmd for complete sequence diagram showing parallel checks and service routing.

## Supported Services

| Service | Client ID Type | External Source | Known Fact Type | Notes |
|---------|----------------|-----------------|-----------------|-------|
| HMRC-MTD-IT | NINO | IF/HIP | PostalCode or Overseas | Also checks MTD-IT-SUPP |
| HMRC-MTD-IT-SUPP | NINO | IF/HIP | PostalCode or Overseas | |
| HMRC-MTD-VAT | VRN | IF/HIP | VatRegistrationDate | |
| HMCE-VATDEC-ORG | VRN | IF/HIP | VatRegistrationDate | |
| HMRC-TERS-ORG | UTR | IF/HIP | PostalCode | |
| HMRC-TERSNT-ORG | URN | IF/HIP | PostalCode | |
| PERSONAL-INCOME-RECORD | NINO | citizen-details | DateOfBirth | |
| HMRC-CGT-PD | CGT Reference | IF/HIP | PostalCode or Overseas | |
| HMRC-PPT-ORG | PPT Reference | IF/HIP | VatRegistrationDate | |
| HMRC-CBC-ORG | CBC ID | IF/HIP | Email | Refined to CBC-NONUKORG if overseas |
| HMRC-PILLAR2-ORG | PLR ID | IF/HIP | PlrReference | |

## Business Logic

### Service Refinement (CBC)

For CBC-ORG service, if client is overseas:
```
service = "HMRC-CBC-NONUKORG"
```

This happens before invitation and relationship checks.

### Multi-Agent Services (MTD-IT)

When service is HMRC-MTD-IT, the endpoint checks **both**:
1. **HMRC-MTD-IT** (main service)
2. **HMRC-MTD-IT-SUPP** (supporting service)

**hasPendingInvitation**: true if invitation pending for **either** service  
**hasExistingRelationshipFor**: Returns first found service ID

### Partial Auth Fallback (MTD-IT/MTD-IT-SUPP only)

If ACR01 relationship check returns NotFound for MTD-IT or MTD-IT-SUPP:
1. Query `partial_auth` MongoDB collection
2. Check for active partial authorization record
3. If found, treat as existing relationship

### Known Fact Extraction

Different services provide different known facts:

- **PostalCode**: From address (spaces removed)
- **DateOfBirth**: From citizen details
- **VatRegistrationDate**: From VAT customer details
- **Email**: From CBC subscription
- **PlrReference**: PLR ID itself
- **Overseas**: Country code if overseas

## Parallel Execution

After retrieving client details, three checks run **in parallel**:

1. **Check pending invitation (main service)**
   - Query `invitations` collection for agent + service + clientId
   - Filter for Pending status

2. **Check pending invitation (supporting service if applicable)**
   - Only for MTD-IT (checks MTD-IT-SUPP as well)
   - Query `invitations` collection

3. **Check existing relationship (main service)**
   - Calls `CheckRelationshipsOrchestratorService` (full ACR01 flow)
   - If not found AND service is MTD-IT/MTD-IT-SUPP: check partial_auth

4. **Check existing relationship (supporting service if applicable)**
   - Only for MTD-IT (checks MTD-IT-SUPP as well)
   - Calls ACR01 + partial_auth fallback

## Dependencies

### External Services

- **citizen-details**: Provides citizen details for PIR service (NINO → name, address, date of birth)
- **IF/HIP**: Provides subscription/registration details for all other services (various endpoints per service)

### Internal Services

- **ClientDetailsService**: Routes to appropriate external API based on service
- **CheckRelationshipsOrchestratorService**: Full ACR01 flow for relationship checking
- **InvitationsRepository**: MongoDB queries for pending invitations
- **PartialAuthRepository**: MongoDB queries for partial authorizations

### Database Collections

- **invitations**: Queries for pending invitations from this agent to this client
- **partial_auth**: Queries for active partial authorizations (MTD-IT/MTD-IT-SUPP only)

## Error Handling

| Scenario | Response | Error Message |
|----------|----------|---------------|
| Client not found | 404 Not Found | - |
| Error retrieving client details | 500 Internal Server Error | "Client details lookup failed - status: '{status}', error: '{message}'" |
| Unexpected error during relationship check | 500 Internal Server Error | "Unexpected error during relationship check" |
| CheckRelationshipInvalidRequest | 500 Internal Server Error (RuntimeException) | "Unexpected error during relationship check" |
| Agent not authenticated | 401 Unauthorized | - |

**Note**: Errors throw RuntimeException (not graceful error handling)

## Use Cases

### 1. Agent Inviting Client - Check for Pending Invitation

**Scenario**: Agent enters client NINO to invite for MTD-IT

**Response**:
```json
{
  "name": "Jane Doe",
  "isOverseas": false,
  "knownFacts": ["SW1A1AA"],
  "knownFactType": "PostalCode",
  "hasPendingInvitation": true,
  "hasExistingRelationshipFor": null
}
```

**Frontend Action**: Show "You have already sent an invitation to Jane Doe for Making Tax Digital for Income Tax"

### 2. Agent Inviting Client - Check for Existing Relationship

**Scenario**: Agent enters client VRN to invite for VAT

**Response**:
```json
{
  "name": "ABC Ltd",
  "isOverseas": false,
  "knownFacts": ["2020-01-15"],
  "knownFactType": "VatRegistrationDate",
  "hasPendingInvitation": false,
  "hasExistingRelationshipFor": "HMRC-MTD-VAT"
}
```

**Frontend Action**: Show "You already have access to ABC Ltd for Making Tax Digital for VAT"

### 3. Agent Verifying Client Details

**Scenario**: Agent wants to confirm client identity before inviting

**Response**:
```json
{
  "name": "John Smith",
  "isOverseas": false,
  "knownFacts": ["SW1A1AA"],
  "knownFactType": "PostalCode",
  "hasPendingInvitation": false,
  "hasExistingRelationshipFor": null
}
```

**Frontend Action**: Display "Is this your client? Name: John Smith, Postcode: SW1A1AA"

### 4. MTD-IT Multi-Service Check

**Scenario**: Agent enters NINO for MTD-IT, but relationship exists for MTD-IT-SUPP

**Response**:
```json
{
  "name": "Jane Doe",
  "isOverseas": false,
  "knownFacts": ["SW1A1AA"],
  "knownFactType": "PostalCode",
  "hasPendingInvitation": false,
  "hasExistingRelationshipFor": "HMRC-MTD-IT-SUPP"
}
```

**Frontend Action**: Show existing relationship for supporting service

## Important Notes

- ✅ **Routes to different external APIs**: Based on service type
- ✅ **MTD-IT multi-service**: Checks both HMRC-MTD-IT and HMRC-MTD-IT-SUPP
- ✅ **CBC refinement**: CBC-ORG → CBC-NONUKORG if overseas
- ✅ **Partial auth fallback**: Only for MTD-IT/MTD-IT-SUPP services
- ✅ **Full ACR01 flow**: Uses complete ACR01 logic for relationship checking
- ✅ **Parallel checks**: Invitation and relationship checks run in parallel
- ✅ **Combined results**: hasPendingInvitation true if **either** main OR supporting has pending
- ✅ **Known facts vary**: Postcode, date of birth, VAT reg date, email - depends on service
- ⚠️ **RuntimeException**: Throws exceptions for errors (not graceful)
- ⚠️ **Spaces removed**: Postcodes have all spaces removed

## Related Documentation

- **ACR01**: Check for relationship (used by this endpoint for relationship checking)
- **citizen-details**: External service for PIR client details
- **IF/HIP**: External service for all other tax service client details

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
