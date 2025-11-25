# ACR08: Get Client Tax Agents Data (Comprehensive Dashboard)

## Overview

⚠️ **Most Complex Endpoint**: This is the most comprehensive and complex endpoint in the service, combining data from **four different sources** to provide a complete client dashboard view.

Retrieves comprehensive tax agent data for the authenticated client, including:
- **Active Authorizations**: Current agent relationships across all services (from ETMP + agent-fi-relationship + MongoDB partial_auth)
- **Pending Invitations**: Outstanding invitation requests (from MongoDB invitations)
- **Authorization Events**: Complete chronological history of all agent authorization events (from all sources)
- **Agent Details**: Enriched with agent agency names and suspension status

This endpoint orchestrates **parallel queries** across multiple services and databases, enriches all data with agent details, and automatically filters out suspended agents.

## API Details

- **API ID**: ACR08
- **Method**: GET
- **Path**: `/client/authorisations-relationships`
- **Authentication**: Client authentication via Government Gateway (retrieves all enrolments + NINO)
- **Audience**: internal
- **Controller**: ClientTaxAgentsDataController
- **Controller Method**: `findClientTaxAgentsData`

## Path Parameters

None

## Query Parameters

None

## Response

### Success Response (200 OK)

Returns comprehensive ClientTaxAgentsData with three main sections:

```json
{
  "agentsInvitations": {
    "agentInvitations": [
      {
        "agentName": "ABC Accountants Ltd",
        "arn": "TARN0000001",
        "invitations": [
          {
            "uid": "ABBBBBBBBBBBB",
            "service": "HMRC-MTD-IT",
            "clientId": "XXIT00000000001",
            "expiryDate": "2025-12-14",
            "status": "Pending"
          },
          {
            "uid": "CCCCCCCCCCCCC",
            "service": "HMRC-MTD-VAT",
            "clientId": "123456789",
            "expiryDate": "2025-11-20",
            "status": "Pending"
          }
        ]
      }
    ]
  },
  "agentsAuthorisations": {
    "agentAuthorisations": [
      {
        "agentName": "XYZ Tax Services",
        "arn": "TARN0000002",
        "authorisations": [
          {
            "uid": "550e8400-e29b-41d4-a716-446655440000",
            "service": "HMRC-MTD-VAT",
            "clientId": "123456789",
            "date": "2024-01-15",
            "arn": "TARN0000002",
            "agentName": "XYZ Tax Services"
          },
          {
            "uid": "550e8400-e29b-41d4-a716-446655440001",
            "service": "HMRC-CGT-PD",
            "clientId": "XMCGTP123456789",
            "date": "2024-03-20",
            "arn": "TARN0000002",
            "agentName": "XYZ Tax Services"
          }
        ]
      },
      {
        "agentName": "Smith & Partners",
        "arn": "TARN0000003",
        "authorisations": [
          {
            "uid": "550e8400-e29b-41d4-a716-446655440002",
            "service": "HMRC-TERS-ORG",
            "clientId": "1234567890",
            "date": "2024-06-10",
            "arn": "TARN0000003",
            "agentName": "Smith & Partners"
          }
        ]
      }
    ]
  },
  "authorisationEvents": {
    "authorisationEvents": [
      {
        "agentName": "XYZ Tax Services",
        "service": "HMRC-MTD-VAT",
        "eventDate": "2024-01-15",
        "eventType": "Accepted"
      },
      {
        "agentName": "XYZ Tax Services",
        "service": "HMRC-CGT-PD",
        "eventDate": "2024-03-20",
        "eventType": "Accepted"
      },
      {
        "agentName": "Smith & Partners",
        "service": "HMRC-TERS-ORG",
        "eventDate": "2024-06-10",
        "eventType": "Accepted"
      },
      {
        "agentName": "Old Agent Ltd",
        "service": "HMRC-MTD-IT",
        "eventDate": "2024-08-05",
        "eventType": "DeAuthorised"
      },
      {
        "agentName": "Another Agent",
        "service": "HMRC-PPT-ORG",
        "eventDate": "2024-09-15",
        "eventType": "Expired"
      },
      {
        "agentName": "Rejected Agent",
        "service": "HMRC-CBC-ORG",
        "eventDate": "2024-10-01",
        "eventType": "Rejected"
      },
      {
        "agentName": "Cancelled Agent",
        "service": "HMRC-PILLAR2-ORG",
        "eventDate": "2024-10-20",
        "eventType": "Cancelled"
      }
    ]
  }
}
```

**Response Explanation**:

- **agentsInvitations**: Shows ABC Accountants Ltd has 2 pending invitations for MTD-IT and MTD-VAT
- **agentsAuthorisations**: Shows 2 active agents:
  - XYZ Tax Services with access to VAT and CGT
  - Smith & Partners with access to Trusts
- **authorisationEvents**: Complete timeline showing:
  - 3 Accepted events (when current agents were authorized)
  - 1 DeAuthorised event (Old Agent Ltd was removed)
  - 1 Expired invitation event
  - 1 Rejected invitation event
  - 1 Cancelled invitation event

### Error Responses

- **400 Bad Request**: Error in request processing
- **401 Unauthorized**: Client not authenticated via Government Gateway
- **403 Forbidden**: User is not an Individual or Organisation
- **500 Internal Server Error**: Unexpected error (e.g., missing dateFrom in authorization)
- **503 Service Unavailable**: Error retrieving agent details or relationships from external services

## Authentication

- **Provider**: GovernmentGateway
- **Affinity Groups**: Individual OR Organisation
- **Retrieval**: `allEnrolments and nino`
- **Unique Feature**: This endpoint retrieves **both** all enrolments AND NINO for comprehensive data gathering

## Service Architecture

See ACR08.mmd for complete sequence diagram.

## Data Sources

This endpoint combines data from **4 different sources**:

1. **MongoDB invitations**: Pending, Expired, Rejected, Cancelled invitations
2. **ETMP (via HIP)**: Active/inactive relationships for enrolled services (excluding PIR)
3. **agent-fi-relationship**: PIR (Personal Income Record) active/inactive relationships
4. **MongoDB partial_auth**: IR-SA partial authorization records

## Response Structure

### agentsInvitations
Shows pending invitations grouped by agent (suspended agents filtered out)

### agentsAuthorisations
Shows active authorizations grouped by agent from all sources (suspended agents filtered out)

### authorisationEvents
Chronological timeline of all events:
- Accepted (from dateFrom)
- DeAuthorised (from dateTo)
- Expired (from invitations)
- Rejected (from invitations)
- Cancelled (from invitations)

## Business Logic

### Suspended Agent Filtering
All agents checked via AgentAssuranceService - suspended agents filtered from all sections

### Partial Auth as Accepted Events
IR-SA partial authorizations appear as "Accepted" events using their created date

### MTD-IT-SUPP Handling
Relationships with auth profile ITSAS001 categorized as HMRC-MTD-IT-SUPP

## Parallel Execution

**Phase 1: Data Gathering (Parallel)**
- Get all invitations from MongoDB
- Get all relationships from ETMP/HIP per service
- Get partial auth from MongoDB

**Phase 2: Enrichment (Parallel)**
- Get agent details for authorizations
- Get agent details for invitations  
- Get agent details for events

## Performance Considerations

- ⚠️ **CAN BE SLOW**: Multiple parallel queries + enrichment
- No caching - all queries fresh
- O(n) agent lookups where n = unique ARNs
- Multiple ETMP queries (one per service)

## Important Notes

- ⚠️ **MOST COMPLEX ENDPOINT**: 4 data sources + parallel enrichment
- ✅ **Includes PIR**: Unlike most endpoints
- ✅ **Includes Invitations**: Only endpoint with invitation data
- ✅ **Includes Partial Auth**: IR-SA partial authorizations
- ✅ **Agent Names**: All data enriched
- ✅ **Event Timeline**: Complete chronological history
- ⚠️ **NO CACHING**: Every request queries fresh

## Comparison with Other Endpoints

| Feature | ACR05 | ACR06 | ACR07 | ACR08 |
|---------|-------|-------|-------|-------|
| Active Relationships | ✅ | ❌ | ✅ (one) | ✅ |
| Invitations | ❌ | ❌ | ❌ | ✅ |
| Events | ❌ | ❌ | ❌ | ✅ |
| Agent Names | ❌ | ❌ | ❌ | ✅ |
| PIR | ❌ | ❌ | ❌ | ✅ |
| Partial Auth | ❌ | ❌ | ❌ | ✅ |

For complete details see ACR08.json and ACR08.mmd

---

## Document Metadata

**Last Updated:** 2025-11-20  
**Git Commit SHA:** `b2138b4e3958677748c1820c3d715d4fbb9d3b2c`  
**Analysis Version:** 1.0
