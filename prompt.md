# Prompt for Documenting MongoDB Collections

You are an expert AI assistant tasked with documenting the MongoDB collections for a microservice.

Your goal is to produce two artifacts:

1. A detailed Markdown file named `mongo.md`.
2. A structured JSON file named `mongo-collections.json` for machine consumption.

**Instructions:**

1. **Analyze the Codebase:**
   - Scan the `app/` directory to find all repository source files (e.g., `*Repository.scala`, `*Repository.java`, etc.).
   - For each repository file, perform the following:
     - Identify the MongoDB collection name (usually a `collectionName` variable).
     - Read the source code to understand the **purpose** of the collection.
     - Analyze the associated data model (e.g., a case class) to determine the **schema**. List the key fields and their descriptions.

2. **Create the Markdown File (`mongo.md`):**
   - Generate a Markdown file that describes each collection.
   - For each collection, include the following sections:
     - `## <Collection Name>`: A level-2 heading with the collection name.
     - `**Repository File:**`: The path to the source file for the repository.
     - `**Purpose:**`: A clear, concise explanation of what the collection is used for.
     - `**Schema Highlights:**`: A bulleted list of important fields and their purpose.
     - `**Sample Document:**`: An illustrative sample document as a `json` code block.

3. **Create the JSON File (`mongo-collections.json`):**
   - Generate a single JSON file that contains structured data for all collections.
   - Use the following structure, populating it with the information you gathered. The `agent-client-relationships` service data is provided as an example of the desired output format.

**JSON Template and Example:**

```json
{
  "serviceName": "agent-client-relationships",
  "collections": [
    {
      "name": "invitations",
      "repositoryFile": "InvitationsRepository.scala",
      "purpose": "This is the primary collection for managing the agent-client invitation workflow. It stores records of invitations sent by agents to clients, allowing the service to track the status of an invitation from creation to acceptance or expiration.",
      "schema": [
        { "field": "invitationId", "description": "A unique identifier for the invitation." },
        { "field": "arn", "description": "The Agent Reference Number of the agent who created the invitation." },
        { "field": "clientId", "description": "The client's identifier (e.g., NINO, UTR)." },
        { "field": "service", "description": "The HMRC service the invitation pertains to (e.g., `HMRC-MTD-IT`, `HMRC-MTD-VAT`)." },
        { "field": "status", "description": "The current state of the invitation (e.g., `Pending`, `Accepted`, `Rejected`, `Expired`)." },
        { "field": "created", "description": "Timestamp of when the invitation was created." },
        { "field": "lastUpdated", "description": "Timestamp of the last status change." },
        { "field": "expiryDate", "description": "The date when the invitation will automatically expire." },
        { "field": "events", "description": "A history of status changes for auditing purposes." }
      ],
      "sampleDocument": {
        "invitationId": "B944641C423A42528B89E43A516E3132",
        "arn": "TARN0000001",
        "clientId": "AB123456A",
        "service": "HMRC-MTD-IT",
        "status": "Pending",
        "created": { "$date": "2025-09-11T10:00:00.000Z" },
        "lastUpdated": { "$date": "2025-09-11T10:00:00.000Z" },
        "expiryDate": { "$date": "2025-10-02T10:00:00.000Z" },
        "events": [
          { "time": { "$date": "2025-09-11T10:00:00.000Z" }, "status": "Pending" }
        ]
      }
    },
    {
      "name": "partial-auth",
      "repositoryFile": "PartialAuthRepository.scala",
      "purpose": "This collection is used to temporarily store information during the 'authorisation' process, where a client is attempting to accept an agent's invitation but may need to complete additional authentication steps. It holds the state while the client is redirected to other services (like Government Gateway) to confirm their identity.",
      "schema": [
        { "field": "credId", "description": "The user's credential ID from Government Gateway." },
        { "field": "clientIds", "description": "A list of client identifiers associated with the user's credentials." },
        { "field": "redirectUrl", "description": "The URL to redirect the user back to after they complete the external authentication steps." }
      ],
      "sampleDocument": {
        "credId": "1234567890123456",
        "clientIds": ["AB123456A", "CD789012B"],
        "redirectUrl": "/agent-client-relationships/some-continue-url"
      }
    },
    {
      "name": "agent-reference",
      "repositoryFile": "AgentReferenceRepository.scala",
      "purpose": "This collection stores a mapping between an agent's `arn` (Agent Reference Number) and their `uid` (a unique identifier, likely related to their Government Gateway account). This allows the service to look up agent details using either identifier.",
      "schema": [
        { "field": "uid", "description": "The agent's unique identifier." },
        { "field": "arn", "description": "The agent's Agent Reference Number." }
      ],
      "sampleDocument": {
        "uid": "some-unique-gateway-id",
        "arn": "TARN0000001"
      }
    },
    {
      "name": "relationship-copy-record",
      "repositoryFile": "RelationshipCopyRecordRepository.scala",
      "purpose": "This collection is used to track the progress of copying client relationships from legacy HMRC systems (like `agent-mapping`) to the modernised `enrolment-store-proxy`. This is a crucial part of the migration strategy to the new agent services platform.",
      "schema": [
        { "field": "arn", "description": "The Agent Reference Number." },
        { "field": "service", "description": "The HMRC service for which relationships are being copied." },
        { "field": "clientIdentifier", "description": "The identifier of the client." },
        { "field": "clientIdentifierType", "description": "The type of the client identifier." },
        { "field": "syncToESStatus", "description": "The status of the synchronisation to the `enrolment-store-proxy` (e.g., `InProgress`, `Success`, `Failed`)." }
      ],
      "sampleDocument": {
        "arn": "TARN0000001",
        "service": "HMRC-MTD-VAT",
        "clientIdentifier": "101747641",
        "clientIdentifierType": "vrn",
        "syncToESStatus": "Success"
      }
    },
    {
      "name": "delete-record",
      "repositoryFile": "DeleteRecordRepository.scala",
      "purpose": "This collection tracks requests to remove or 'de-authorise' agent-client relationships. When a relationship is terminated, a record is stored here to keep a log of these events and potentially to manage the cleanup of corresponding enrolments in downstream systems.",
      "schema": [
        { "field": "arn", "description": "The Agent Reference Number." },
        { "field": "service", "description": "The relevant HMRC service." },
        { "field": "clientIdentifier", "description": "The client's identifier." },
        { "field": "clientIdentifierType", "description": "The type of the client identifier." },
        { "field": "syncToESStatus", "description": "The status of the de-authorisation synchronisation with the `enrolment-store-proxy`." },
        { "field": "lastFound", "description": "Timestamp of the last time the record was processed." }
      ],
      "sampleDocument": {
        "arn": "TARN0000001",
        "service": "HMRC-MTD-IT",
        "clientIdentifier": "AB123456A",
        "clientIdentifierType": "ni",
        "syncToESStatus": "InProgress",
        "lastFound": { "$date": "2025-09-11T11:00:00.000Z" }
      }
    },
    {
      "name": "recovery-schedule",
      "repositoryFile": "RecoveryScheduleRepository.scala",
      "purpose": "This collection is used for managing scheduled, asynchronous recovery tasks. If an operation (like creating a relationship in a downstream service) fails, a record can be created here to retry the operation later. This makes the system more resilient to temporary failures in other microservices.",
      "schema": [
        { "field": "arn", "description": "The Agent Reference Number." },
        { "field": "service", "description": "The HMRC service." },
        { "field": "clientIdentifier", "description": "The client's identifier." },
        { "field": "clientIdentifierType", "description": "The type of the client identifier." },
        { "field": "action", "description": "The action to be retried (e.g., `create-relationship`)." },
        { "field": "tryCount", "description": "The number of times the recovery has been attempted." },
        { "field": "nextTryTime", "description": "The timestamp for the next scheduled retry attempt." }
      ],
      "sampleDocument": {
        "arn": "TARN0000001",
        "service": "HMRC-MTD-IT",
        "clientIdentifier": "AB123456A",
        "clientIdentifierType": "ni",
        "action": "create-relationship",
        "tryCount": 2,
        "nextTryTime": { "$date": "2025-09-11T12:00:00.000Z" }
      }
    }
  ]
}
```
