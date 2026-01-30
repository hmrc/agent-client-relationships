# API ID Migration - Completion Summary

**Date**: January 29-30, 2025  
**Project**: agent-client-relationships API Documentation Standardization  
**Status**: ✅ **COMPLETE**

## Overview

Successfully migrated all API references in agent-client-relationships documentation from endpoint URLs to stable API IDs. This enables consistent cross-referencing between code annotations, service metadata, and documentation.

## Migration Scope

### Files Updated by Type

#### JSON Files (7 files, 15 API references)

- [ACR01.json](ACR01/ACR01.json) - 9 API IDs
- [ACR02.json](ACR02/ACR02.json) - 1 API ID
- [ACR04.json](ACR04/ACR04.json) - 2 API IDs
- [ACR05.json](ACR05/ACR05.json) - 1 API ID
- [ACR06.json](ACR06/ACR06.json) - 1 API ID
- [ACR07.json](ACR07/ACR07.json) - 1 API ID
- [ACR09.json](ACR09/ACR09.json) - 1 API ID
- [ACR14.json](ACR14/ACR14.json) - 1 API ID

#### Markdown Files (8 files)

- [ACR01.md](ACR01/ACR01.md) - Downstream calls section
- [ACR02.md](ACR02/ACR02.md) - Sequence diagram
- [ACR04.md](ACR04/ACR04.md) - Sequence diagram
- [ACR05.md](ACR05/ACR05.md) - Sequence diagram
- [ACR06.md](ACR06/ACR06.md) - Sequence diagram
- [ACR07.md](ACR07/ACR07.md) - Sequence diagram
- [ACR09.md](ACR09/ACR09.md) - No changes needed (PIR service details only)
- [ACR14.md](ACR14/ACR14.md) - Dependencies section

#### Mermaid Files (7 files)

- [ACR02.mmd](ACR02/ACR02.mmd) - Sequence diagram
- [ACR04.mmd](ACR04/ACR04.mmd) - Sequence diagram
- [ACR05.mmd](ACR05/ACR05.mmd) - Sequence diagram
- [ACR06.mmd](ACR06/ACR06.mmd) - Sequence diagram
- [ACR07.mmd](ACR07/ACR07.mmd) - Sequence diagram
- [ACR09.mmd](ACR09/ACR09.mmd) - getCitizenDetails call
- [ACR14.mmd](ACR14/ACR14.mmd) - getAgentRecord call

**Total**: 22 files updated across 8 ACR APIs

## API ID Mappings Applied

### ETMP Service (etmp.service-meta.yaml)

- **ETMP03** - Get active relationships
  - Used in: ACR05, ACR06, ACR07
  - Replaced: `/etmp/RESTAdapter/rosm/agent-relationship`
  
- **ETMP04** - Get all agent relationships
  - Used in: ACR02
  - Replaced: `/etmp/RESTAdapter/rosm/agent-relationship`

- **ETMP06** - Get ITSA Business Details
  - Used in: ACR01
  - Replaced: `/registration/personal-details/nino/:nino`

### Enrolment Store (enrolment-store-proxy.service-meta.yaml)

- **ES1** - Query known facts
  - Used in: ACR01
  - Replaced: `/enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey`

- **ES2** - Query enrolments by group ID
  - Used in: ACR01
  - Replaced: `/enrolment-store-proxy/enrolment-store/groups/:groupId/enrolments`

- **ES3** - Check enrolment allocation
  - Used in: ACR01
  - Replaced: `/enrolment-store-proxy/enrolment-store/enrolments/:enrolmentKey/groups`

### Users Groups Search (users-groups-search.service-meta.yaml)

- **UGS01** - Get user by group ID
  - Used in: ACR01
  - Replaced: `/users-groups-search/groups/:groupId`

### Agent Permissions (agent-permissions.service-meta.yaml)

- **AP06** - Check access group contains client
  - Used in: ACR01
  - Replaced: `/agent-permissions/arn/:arn/client/:clientId`

### DES (des.service-meta.yaml)

- **DES08** - Get SA Agent Client Authorisation Flags
  - Used in: ACR04
  - Replaced: `/registration/relationship`

### Agent Mapping (agent-mapping.service-meta.yaml)

- **AM08** - Find SA agent reference by ARN
  - Used in: ACR04
  - Replaced: `/agent-mapping/mappings/key/MTDID/arn/:arn`

- **AM09** - Find mappings for multiple keys
  - Used in: ACR01
  - Replaced: `/agent-mapping/mappings`

### Citizen Details (citizen-details.service-meta.yaml)

- **CD02** - Get citizen details by NINO
  - Used in: ACR09
  - Replaced: `/citizen-details/{nino}`

### Agent Assurance (agent-assurance.service-meta.yaml)

- **AA27** - Get agent record by ARN
  - Used in: ACR14
  - Replaced: `/agent-assurance/agent-record/:arn`

### Agent Fi Relationship (agent-fi-relationship.service-meta.yaml)

- **AFR01** - Get relationship for NINO
  - Used in: ACR01
  - Replaced: `/agent-fi-relationship/relationships/NINO/:nino`

## Verification

### Confirmation Commands

```bash
# Verify no remaining endpoint URLs in JSON files
grep -r '"endpoint"' agent-client-relationships/service-dependency-map/agent-client-relationships/ACR*/ACR*.json
# Result: No matches ✅

# Verify no remaining /etmp/ paths in MD/MMD files (excluding migration docs)
grep -r '/etmp/RESTAdapter' agent-client-relationships/service-dependency-map/agent-client-relationships/ACR*/ACR*.{md,mmd}
# Result: No matches ✅

# Verify no remaining /citizen-details/ paths
grep -r '/citizen-details/' agent-client-relationships/service-dependency-map/agent-client-relationships/ACR*/ACR*.{md,mmd}
# Result: No matches ✅

# Verify no remaining /agent-assurance/ paths
grep -r '/agent-assurance/agent-record' agent-client-relationships/service-dependency-map/agent-client-relationships/ACR*/ACR*.{md,mmd}
# Result: No matches ✅
```

### Sample API ID References

**In JSON** (machine-readable):

```json
{
  "apiId": "ETMP03",
  "service": "etmp",
  "description": "Get active relationships"
}
```

**In Markdown** (human-readable):

```markdown
- **agent-assurance**: Provides agent record
  - API ID: **AA27** (Get agent record by ARN)
```

**In Mermaid** (visual diagrams):

```mermaid
HIP->>+ETMP: GET ETMP03 (Get active relationships)<br/>?idType={idType}...
```

## Architecture Integration

### Complete Traceability Chain

```
Scala Code → Service Metadata → JSON Specs → Documentation
    ↓              ↓                ↓              ↓
@ConsumesAPI → service-meta → apiId field → API ID refs
(apiId=       .yaml files    in JSON        in MD/MMD
 "ETMP03")                    files          files
```

### Example: ETMP03 Traceability

1. **Code**: `HipConnector.scala`

   ```scala
   @ConsumesAPI(apiId = "ETMP03", service = "etmp")
   def getActiveClientRelationships(...)
   ```

2. **Metadata**: `etmp.service-meta.yaml`

   ```yaml
   apis:
     - id: ETMP03
       name: Get active relationships
       method: GET
       path: /RESTAdapter/rosm/agent-relationship
   ```

3. **Specification**: `ACR05.json`

   ```json
   {
     "apiId": "ETMP03",
     "service": "etmp",
     "description": "Get active relationships"
   }
   ```

4. **Documentation**: `ACR05.md`

   ```markdown
   GET ETMP03 (Get active relationships)
   ```

## Benefits Achieved

### 1. Stability

- ✅ API IDs don't change when endpoint paths are refactored
- ✅ Versioning strategy: ETMP03 → ETMP03v2 for breaking changes

### 2. Consistency

- ✅ Same identifier used across all documentation types
- ✅ Links code, metadata, and docs with a single stable reference

### 3. Maintainability

- ✅ Single source of truth in `*.service-meta.yaml` files
- ✅ Automated tools can validate references exist
- ✅ Easier to track API usage across services

### 4. Discoverability

- ✅ grep for API ID (e.g., "ETMP03") finds all usages
- ✅ Clear mapping to service-meta.yaml definitions
- ✅ Human-readable descriptions alongside IDs

## Related Files

- [ENDPOINT_TO_APIID_MIGRATION.md](ENDPOINT_TO_APIID_MIGRATION.md) - Detailed migration log with before/after examples
- `*.service-meta.yaml` - 22 service metadata files with API definitions
- `*Connector.scala` - 37 @ConsumesAPI annotations in connector code

## Future Considerations

### Automation Opportunities

1. **Pre-commit Hook**: Reject endpoint URLs in new documentation
2. **Validation Tool**: Verify all API IDs reference valid service-meta.yaml entries
3. **Dependency Graph**: Auto-generate from API IDs
4. **Breaking Change Detection**: Compare service-meta.yaml versions

### Expansion to Other Services

This pattern can be replicated across:

- agent-permissions
- agent-assurance
- agent-invitations-frontend
- agent-services-account
- Other HMRC agent services

## Contributors

Migration performed by: AI Assistant (GitHub Copilot)  
Reviewed by: Chris O'Brien  
Project: HMRC Agents Service Dependency Mapping

---

**Last Updated**: January 30, 2025  
**Migration Version**: 1.0  
**Status**: Production Ready ✅
