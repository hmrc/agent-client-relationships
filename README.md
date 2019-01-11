# agent-client-relationships

[![Build Status](https://travis-ci.org/hmrc/agent-client-relationships.svg)](https://travis-ci.org/hmrc/agent-client-relationships) [ ![Download](https://api.bintray.com/packages/hmrc/releases/agent-client-relationships/images/download.svg) ](https://bintray.com/hmrc/releases/agent-client-relationships/_latestVersion)

This is a backend microservice whose domain is Agent Client Relationships.
It is used to manage relationships between agents and clients for the following services:
 - HMRC-MTD-IT
 - HMRC-MTD-VAT
 - HMRC-NI-ORG

## Running the tests

    sbt test it:test

## Running the app locally

    sm --start AGENT_MTD -f
    sm --stop AGENT_CLIENT_RELATIONSHIPS
    ./run-local
    
## Design Documentation

[Recovery from Failures whilst Creating Relationships](docs/recovery.md).

## Proposed API

We're still building this service so some/all of the API described here might not be implemented yet!

### Check whether a relationship exists between an Agent and a client

HMRC-MTD-IT service:

    GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid
    
    GET /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino
    
HMRC-MTD-VAT service:    
    
    GET /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn
    
HMRC-NI-ORG service:    
    
    GET /agent/:arn/service/HMRC-NI-ORG/client/EORI/:eori

These endpoints check whether the agent represented by the arn is allocated to the client represented by the mtdItId, nino, vrn or eori 
within Government Gateway or in CESA.
  
Possible responses:

#### OK

If the agent is allocated to the client then a 200 OK response with no JSON body will be returned 

#### Not Found

If the agent is not allocated to the client then a 404 Not Found will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided error code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND". 

### Check whether a relationship exists between an Agent and a client for service IR-SA

    GET /agent/:arn/service/IR-SA/client/ni/:nino

This endpoint checks whether the agent represented by the arn is allocated to the client represented by the nino 
within Government Gateway or in CESA.
  
Possible responses:

#### OK

If the agent is allocated to the client then a 200 OK response with no JSON body will be returned 

#### Not Found

If the agent is not allocated to the client then a 404 Not Found will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided error code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND".
 
### Create a relationship between an Agent and a client

HMRC-MTD-IT service:

    PUT /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtdItId
    
HMRC-MTD-VAT service:    
    
    PUT /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn
    
HMRC-NI-ORG service:    
    
    PUT /agent/:arn/service/HMRC-NI-ORG/client/EORI/:eori
    
These endpoints allocate the agent represented by the arn to the client represented by the mtditid, vrn or eori 
within GovernmentGateway or in CESA.

Possible responses:

### Created

If the agent is allocated to the client then a 201 Created will be returned with an empty body

### NotFound

If the relationship could not be created then 404 NotFound will be returned with the failure message 
as body to help diagnose the issue
 
### Delete a relationship between an Agent and a client

HMRC-MTD-IT service:

    DELETE /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid
    
    DELETE /agent/:arn/service/HMRC-MTD-IT/client/NI/:nino
    
HMRC-MTD-VAT service:    
    
    DELETE /agent/:arn/service/HMRC-MTD-VAT/client/VRN/:vrn
    
HMRC-NI-ORG service:    
    
    DELETE /agent/:arn/service/HMRC-NI-ORG/client/EORI/:eori  

These endpoints de-allocate the agent represented by the arn from the client represented by the mtdItId, nino, vrn or eori 
within Government Gateway.
  
Possible responses:

#### NoContent

If the agent is de allocated from the client then a 204 NoContent will be returned with empty body

#### Not Found

If de allocation fails then a 404 NotFound will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided error code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND". 

### Get list of existing relationships for the current authenticated individual or organisation 

    GET /relationships/active

This endpoint returns a map of existing relationships for the current user.
  
Possible responses:

#### OK

    {
        "HMRC-MTD-IT": ["AARN0000002"],
        "HMRC-MTD-VAT": ["AARN0000006", "AARN0000004"]
    }
    
or empty object if none relationship exists

### Future development

It is anticipated that additional methods for other services will be added and will use a similar url structure.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")

