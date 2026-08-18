package com.example.data.repository

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/**
 * Reads and writes the task and block records inside a baseline.
 *
 * It reuses the journal's own field encoding rather than inventing a second format, so a baseline
 * decodes under exactly the rules that already guard Undo — including its refusal to half-apply a
 * malformed record. A baseline that silently loses a field would be worse than no baseline, because
 * the variance it reports would look precise.
 */
object PlanBaselineCodec {
  /** Keyed by id, exactly as the journal stores its own records. */
  fun encodeItems(items: List<PlanItem>): String =
    SavedViewCodec.encodeStringObject(
      items.associate { item ->
        item.id to PlanMutationCodec.encodeStateRecord(item.toMutationState())
      }
    )

  fun encodeBlocks(blocks: List<PlanBlock>): String =
    SavedViewCodec.encodeStringObject(
      blocks.associate { block ->
        block.id to PlanMutationCodec.encodeStateRecord(block.toMutationState())
      }
    )

  /** Throws when a record is malformed; a partial baseline is never returned. */
  fun decodeItems(payload: String): List<PlanItem> =
    SavedViewCodec.decodeStringObject(payload).map { (id, record) ->
      PlanMutationCodec.decodeItemRecordFor(id, record).toModel()
    }

  fun decodeBlocks(payload: String): List<PlanBlock> =
    SavedViewCodec.decodeStringObject(payload).map { (id, record) ->
      PlanMutationCodec.decodeBlockRecordFor(id, record).toModel()
    }
}
