# Recovery of Failures whilst Creating HMRC-MTD-IT Relations in ETMP and Government Gateway

## Scope

Recovery from agent-client-relationships crashes whilst an HMRC-MTD-IT relationship is being created in ETMP and GG based on a relationship in CESA (informally, "copying a relationship from CESA to ETMP and GG").  

## Out of Scope (for now - we want to address these later)

* Recovery from ETMP or GG failures whilst creating a relationship.
* Recovery from agent-client-relationships crashes whilst an HMRC-MTD-IT relationship is being created based on a PUT to the relationship endpoint.

## Success Criteria

1. If agent-client-relationships crashes whilst copying a relationship from
   CESA to ETMP/GG then the next time a GET to a-c-r is made for that 
   relationship then we should return 200 not 404.
2. If agent-client-relationships crashes whilst copying a relationship from 
   CESA to ETMP/GG then we should eventually create the relationship in ETMP and 
   GG so that when helpdesk staff check ETMP they will see the relationship.
3. (APB-795) If a relationship is created in ETMP/GG based on a CESA relationship and 
   then the client or agent terminates the ETMP/GG relationship then we should 
   return 404 not 200. In other words we should only copy the relationship from 
   CESA once, as a data migration, and if after that the ETMP/GG relationship
   is removed then we should respect that.
4. ??? is this necessary ??? We should only create GG relationships if the ETMP
   relationship exists, i.e. we should preserve the invariant that if a 
   relationship exists in GG then the corresponding ETMP relationship also exists.
5. We should handle concurrency. For example if two checks of the same 
   relationship needing recovery happen concurrently then we should do something sensible.
   
## Scenarios
### APB-1108

Agent-client-relationships during relationship copy. It crashed after:

* RelationshipCopyRecord was created in MongoDB.
* RelationshipCopyRecord.syncToETMPStatus was set to InProgress.
* Relationship was created in ETMP.

...but before:

* RelationshipCopyRecord.syncToETMPStatus was set to Success.
* RelationshipCopyRecord.syncToGGStatus was updated or a relationship was created in GG.

## Design

In `checkCesaForOldRelationshipAndCopy`, if we find a RelationshipCopyRecord 
that is `actionRequired` (`syncToETMPStatus != Success` or `syncToGGStatus != Success`) then this triggers a recovery process.

The recovery process (`recoverRelationshipCreation`) calls `createEtmpRecord` 
if `syncToETMPStatus != Success` and if this does not fail calls 
`createGgRecord` if `syncToGGStatus != Success`.

The fact that we only continue to `createGgRecord` if `createEtmpRecord` does 
not fail ensures that we meet success criterion (4) (if a relationship exists 
in GG then the corresponding ETMP relationship also exists.).

However `createEtmpRecord` will fail in the APB-1108 scenario because the DES 
API to create a relationship in ETMP returns a 400 status code if the 
relationship already exists.  We would usually treat this as an error and 
therefore not continue to create the GG relationship (thus not meeting success 
criterion (2)).

So why don't we treat a 400 status code as success and continue to create the 
relationship in GG? 400 is also returned in other circumstances, e.g. when the 
format of the ARN is invalid, so we can't tell why the DES call failed.
However what we can do is when we receive a 400 when calling DES to create an
ETMP relationship call DES to *check for the existence of the relationship* and
if it exists treat this as a success and continue. If it does not exist we 
treat it as a failure.

## Concurrency

If 2 check relationship requests come in for the same relationship at similar 
times, and the relationship needs recovery, then it is possible that 2 attempts
will be made to create the ETMP and GG relationships.

For ETMP this will be OK because if we fail to create the relationship initially
we check whether the relationship exists and if so consider this to be success.

For GG we need to find out whether delegating an enrolment is idempotent. If so
then the above design is sufficient. If not then we need to use some kind of
lock to ensure that only one thread attempts recovery concurrently.

It is probably worth doing locking anyway, for performance (avoid lots of API 
calls) and predictability.

We can use [mongo-lock](https://github.com/hmrc/mongo-lock) for this. We need
to use `ExclusiveTimePeriodLock` to ensure that a lock is not held forever
if a-c-r crashes.

If the lock is already taken then we should confirm or deny the existence of 
the relationship based on the state of the RelationshipCopyRecord alone. We
need to be careful to meet criterion (3), so should deny the existence of the
relationship if etmpSyncStatus and ggSyncStatus are both Success (because in 
this state we know that the relationship made it into ETMP/GG so we should
be exclusively using ETMP/GG data, not CESA data). 
