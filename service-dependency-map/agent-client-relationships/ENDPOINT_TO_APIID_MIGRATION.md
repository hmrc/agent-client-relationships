# Endpoint to API ID Migration Summary

## Overview

Successfully migrated all downstream API endpoint references in ACR JSON files from URL paths to standardized API IDs defined in the service-meta.yaml taxonomy.

## Migration Date

29 January 2026

## Files Modified

- **Total ACR files processed**: 34 (ACR01-ACR34)
- **Files with endpoint references**: 7
- **Total endpoint references updated**: 15

## Detailed Changes

### ACR01: Check Agent-Client Relationship

- **ES3**: `/enrolment-store-proxy/enrolment-store/groups` → Get principal group ID
- **ES1**: `/enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups` → Get delegated groups
- **UGS01**: `/users-groups-search/groups/:groupId/users` → Get group users
- **AP06**: `/agent-permissions/arn/:arn/client/:enrolmentKey/groups` → Check client assignment
- **ES2**: `/enrolment-store-proxy/enrolment-store/users/:userId/enrolments` → Get user enrolments
- **ETMP06**: `/individuals/details/nino/:nino` → Get MTD IT ID from NINO
- **DES08**: `/sa/agents/nino/:nino` → Get SA agent references
- **AM09**: `/agent-mapping/mappings/sa/:arn` → Get agent's SA references
- **AFR01**: `/agent-fi-relationship/relationships/agent/:arn/service/:service/client/:clientId` → Check PIR relationship

### ACR02: Get Inactive Agent Relationships

- **ETMP04**: `/etmp/RESTAdapter/rosm/agent-relationship` → Get all agent relationships (active/inactive)

### ACR04: Get Inactive Client Relationships for Agent

- **DES08**: `/registration/relationship/nino/{nino}` → Get client SA relationships
- **AM08**: `/agent-mapping/mappings/{arn}` → Get agent mapping

### ACR05: Get Active Client Relationships

- **ETMP03**: `/etmp/RESTAdapter/rosm/agent-relationship` → Get active relationships

### ACR06: Get Active Agent Relationships

- **ETMP03**: `/etmp/RESTAdapter/rosm/agent-relationship` → Get active agent relationships

### ACR07: Get Agent Relationships by Service

- **ETMP03**: `/etmp/RESTAdapter/rosm/agent-relationship` → Get relationships by service

### ACR09: Check Known Facts (External Dependencies)

- **CD02**: `/citizen-details/{nino}` → Get citizen details for PIR service

### ACR14: Get Agent Details (External Dependencies)

- **AA27**: `/agent-assurance/agent-record/:arn` → Get agent record with suspension status

## API ID Mappings Used

### Enrolment Store Proxy (ES)

- ES1: Get delegated groups for enrolment
- ES2: Get user's enrolments
- ES3: Get principal group ID for agent

### ETMP

- ETMP03: Get active agent-client relationships
- ETMP04: Get all relationships (active + inactive)
- ETMP06: Get MTD IT ID from NINO

### Agent Services

- **agent-permissions (AP)**:
  - AP06: Check if client is assigned to access groups
  
- **agent-fi-relationship (AFR)**:
  - AFR01: Get PIR relationship
  
- **agent-assurance (AA)**:
  - AA27: Get agent record with checks
  
- **agent-mapping (AM)**:
  - AM08: Get agent mappings
  - AM09: Get SA agent references for ARN

### Platform Services

- **DES**:
  - DES08: Get SA agent references by NINO
  
- **citizen-details (CD)**:
  - CD02: Get citizen details by NINO
  
- **users-groups-search (UGS)**:
  - UGS01: Get users in a group

## Benefits

1. **Consistency**: All downstream API calls now reference standardized API IDs
2. **Traceability**: API IDs link directly to service-meta.yaml definitions
3. **Maintainability**: Changing an API endpoint only requires updating the service-meta.yaml
4. **Documentation**: API IDs provide a stable reference point across documentation
5. **Automation**: Enables automated dependency graph generation and validation

## Verification

All endpoint fields have been replaced with apiId fields. No JSON files contain the "endpoint" field anymore.

```bash
# Verification command (returns empty):
grep -c '"endpoint"' ACR*/ACR*.json | grep -v ':0$'
```

## Script Used

The migration was performed using `update_api_ids.py` which:

1. Maps endpoint URL patterns to API IDs
2. Processes both `interactions` and `externalDependencies` sections
3. Preserves all other JSON fields and formatting
4. Reports all changes made
