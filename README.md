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


## Running the tests

    ./check.sh


## Running the app locally

    sm2 --start AGENT_AUTHORISATION -r
    sm2 --stop AGENT_CLIENT_RELATIONSHIPS
    ./run.sh

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")

