# agent-client-relationships

[![Build Status](https://travis-ci.org/hmrc/agent-client-relationships.svg)](https://travis-ci.org/hmrc/agent-client-relationships) [ ![Download](https://api.bintray.com/packages/hmrc/releases/agent-client-relationships/images/download.svg) ](https://bintray.com/hmrc/releases/agent-client-relationships/_latestVersion)

This is a backend microservice whose domain is Agent Client Relationships.

## Running the tests

    sbt test it:test

## Running the app locally

    sm --start AGENT_MTD -f
    sm --stop AGENT_CLIENT_RELATIONSHIPS
    ./run-local

## Proposed API

We're still building this service so some/all of the API described here might not be implemented yet!

### Check whether a relationship exists between an Agent and a client for service HMRC-MTD-IT

    GET /agent/:arn/service/HMRC-MTD-IT/client/MTDITID/:mtditid

This endpoint checks whether the agent represented by the arn is allocated to the client represented by the mtdItId 
within Government Gateway.
  
Possible responses:

#### OK

If the agent is allocated to the client then a 200 OK response with no JSON body will be returned 

#### Not Found

If the agent is not allocated to the client then a 404 Not Found will be returned with a JSON structure as follows:

    {
      "code": "RELATIONSHIP_NOT_FOUND"
    }

The provided code is to help diagnose potential issues in production and will usually be "RELATIONSHIP_NOT_FOUND". 


### Future development

It is anticipated that additional methods for other services will be added and will use a similar utl structure.


### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")