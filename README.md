# agent-client-relationships

[![Build Status](https://travis-ci.org/hmrc/agent-client-relationships.svg)](https://travis-ci.org/hmrc/agent-client-relationships)

This is a backend microservice whose domain is Agent Client Relationships. Relationships are established after a client accepts an invitation.

It is used to manage relationships between agents and clients for the following services:
 - HMRC-MTD-IT
 - HMRC-MTD-VAT
 - HMRC-TERS-ORG / HMRC-TERSNT-ORG (Trusts and estates)
 - IR-SA 
 - HMCE-VATDEC-ORG
 - HMRC-CGT-PD
 - HMRC-PPT-ORG
 - HRMC-CBC-ORG / HMRC-CBC-NONUK-ORG (Country by country, support pending)

### Agent access groups (new)

The relationships between a client and agent can be checked at agency level (default, if access groups turned off).

If an agency has turned on access groups then the relationship will be checked at the agent user level.
* the client has a relationship with the agency AND the client and the agent user belong to the same access group or the client does not belong to any access group.

## Design Documentation

[Recovery from Failures whilst Creating Relationships](docs/recovery.md).

## API

### Check whether a relationship exists between an Agent and a client

Note: now includes optional :userId to check the relationship at the user level, rather than agency level.

`GET              /agent/:arn/service/:service/client/:clientIdType/:clientId`

Possible responses:

`OK` - If the agent is allocated to the client then a 200 OK response with no JSON body will be returned 

`Not Found` - If the agent is not allocated to the client then a 404 Not Found will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided error code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND".

### Create relationship exists between an Agent and a client
`PUT              /agent/:arn/service/:service/client/:clientIdType/:clientId`

Possible responses :
 
 `Created` - If the agent is allocated to the client then a 201 Created will be returned with an empty body
 
 `NotFound` - If the relationship could not be created then 404 NotFound will be returned with the failure message 
 as body to help diagnose the issue
 
### Delete a relationship exists between an Agent and a client
`DELETE           /agent/:arn/service/:service/client/:clientIdType/:clientId`

Possible responses:

`NoContent` - If the agent is de allocated from the client then a 204 NoContent will be returned with empty body

`Not Found` - If de allocation fails then a 404 NotFound will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided error code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND". 

#### The valid combinations for service and clientIdTypes for GET/PUT/DELETE endpoints are as follows: 

 | Service         | ClientIdType |
 | -------------   | ------------ |
 | HMRC-MTD-IT     | MTDITID or NI|
 | HMRC-MTD-VAT    | VRN          |
 | HMRC-TERS-ORG   | SAUTR        |
 | IR-SA           | ni           |
 | HMCE-VATDEC-ORG | vrn          |

### Retrieve client details

Returns a name, status, overseas flag, and known facts for a client.

`GET /client/:service/details/:clientId`

Successful response (status code 200):
```
{
  "name": "Example name",
  "status": "Insolvent",
  "isOverseas": true,
  "knownFacts": ["2020-01-01"],
  "knownFactType": "Date"
}
```
Where the fields "status", "knownFacts" and "knownFactType" are optional.

"status" is an enum with possible values: `"Insolvent"`, `"Deregistered"`, `"Inactive"`

"knownFactType" is an enum with possible values: `"Date"`, `"Email"`, `"PostalCode"`, `"CountryCode"`

Error responses:
- If the API call did not return the necessary client details, a status of 404 with no body is returned.
- If there was an unexpected failure when calling the relevant API, a status of 500 with no body is returned.

### Create authorisation request

Creates a new authorisation request, also known as an invitation, and returns the invitation ID.

`POST /agent/:arn/authorisation-request`

Example request body:
```
{
  "clientId": "123456789",
  "suppliedClientIdType": "vrn",
  "clientName": "Client Namerson",
  "service": "HMRC-MTD-VAT",
  "clientType": "personal"
}
```

Example success response (status code 201)
```
{
  "invitationId": "ABC123"
}
```

Error responses:
- Status 400:
  - Invalid client ID (body includes `"code": "INVALID_CLIENT_ID"`)
  - Client ID type is not supported (body includes `"code": "UNSUPPORTED_CLIENT_ID_TYPE"`)
  - Service key is not supported (body includes `"code": "UNSUPPORTED_SERVICE"`)
  - Client type is not supported (body includes `"code": "UNSUPPORTED_CLIENT_TYPE"`)
  - Invalid JSON (no body)
- Status 403:
  - Client registration could not be found (body includes `"code": "CLIENT_REGISTRATION_NOT_FOUND"`)
  - An invitation has already been created for these details (body includes `"code": "DUPLICATE_AUTHORISATION_REQUEST"`)

### Validate client invitation

Validates that the expected client invitation exists, and returns details regarding the invitation and the associated agent. 

`POST /client/validate-invitation`

Example request body:
```
{
  "uid":"1234567",
  "serviceKeys":["HMRC-MTD-IT", "HMRC-NI", "HMRC-PT"]
}
```

Example success response (status code 200):
```
{
  "invitationId": "ABC123",
  "serviceKey": "HMRC-MTD-IT",
  "agentName": "ABC Accountants",
  "status": "Pending",
  "lastModifiedDate": "2020-01-01T00:00:00Z",
  "existingMainAgent": {
    "agencyName": "XYZ Accountants",
    "sameAgent": true
  },
  "clientType": "personal"
}
```

Error responses:
- If the associated agent is suspended, a status of 403 with no body is returned.
- If the invitation or the relevant agent reference record could not be found, a status of 404 with no body is returned.

## Running the tests

    ./check.sh

### Automated testing
This service is tested by the following automated test repositories:
- [agent-helpdesk-ui-tests](https://github.com/hmrc/agent-helpdesk-ui-tests)
- [agent-gran-perms-acceptance-tests](https://github.com/hmrc/agent-gran-perms-acceptance-tests/)
- [agent-services-account-ui-tests](https://github.com/hmrc/agent-services-account-ui-tests)
- [agent-authorisation-api-acceptance-tests](https://github.com/hmrc/agent-authorisation-api-acceptance-tests)

## Running the app locally

    sm2 --start AGENT_AUTHORISATION
    sm2 --stop AGENT_CLIENT_RELATIONSHIPS
    ./run.sh

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")

