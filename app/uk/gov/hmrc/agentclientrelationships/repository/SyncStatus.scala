package uk.gov.hmrc.agentclientrelationships.repository

import play.api.libs.json.{Format, Reads, Writes}

object SyncStatus extends Enumeration {
  type SyncStatus = Value
  val InProgress, IncompleteInputParams, Success, Failed = Value

  implicit val formats = Format[SyncStatus](Reads.enumNameReads(SyncStatus), Writes.enumNameWrites)
}
