package com.example.data.database

import com.example.data.prefs.SettingKeys

/**
 * Pure, idempotent conversion of name-based legacy board data into stable planning IDs.
 *
 * Split out of `PlanMigration.kt` so the Room migration file holds nothing but migrations. The
 * identity helpers it delegates to live in `planning-core` (`LegacyNameKeys`) because the journal
 * codecs must validate the same keys; `SettingKeys` and the setting encodings stay app-owned.
 */
internal data class LegacyPlanBoard(
  val id: String,
  val name: String,
  val nameKey: String,
  val rank: Long,
  val isDefault: Boolean,
  val columns: List<LegacyPlanColumn>,
)

internal data class LegacyPlanColumn(
  val id: String,
  val boardId: String,
  val name: String,
  val nameKey: String,
  val rank: Long,
)

internal data class LegacyPlanCatalog(
  val boards: List<LegacyPlanBoard>,
  val activeBoardId: String,
)

internal object LegacyPlanCatalogBuilder {
  fun build(
    encodedBoards: String?,
    activeBoardName: String?,
    encodedColumns: (String) -> String?,
    eventDestinations: List<Pair<String, String>>,
  ): LegacyPlanCatalog {
    val displayNames = mutableListOf<String>()
    displayNames +=
      SettingKeys.decodeList(encodedBoards)?.ifEmpty { null }
        ?: listOf(SettingKeys.DEFAULT_BOARD)
    displayNames += eventDestinations.map { it.first }

    val boardNames =
      displayNames
        .mapNotNull(::validDisplayName)
        .distinct()
        .ifEmpty { listOf(SettingKeys.DEFAULT_BOARD) }
    val boardKeys = collisionSafeNameKeys(boardNames, preferredCanonical = SettingKeys.DEFAULT_BOARD)

    val boards =
      boardNames.mapIndexed { boardIndex, boardName ->
        val boardKey = boardKeys.getValue(boardName)
        val boardId = stableId("legacy-board:$boardKey")
        val configured = SettingKeys.decodeList(encodedColumns(boardName))
        val eventColumns =
          eventDestinations
            .filter { it.first == boardName }
            .mapNotNull { validDisplayName(it.second) }
        val columnNames =
          ((configured ?: SettingKeys.DEFAULT_COLUMNS) + eventColumns)
            .mapNotNull(::validDisplayName)
            .distinct()
            .ifEmpty { SettingKeys.DEFAULT_COLUMNS }
        val columnKeys = collisionSafeNameKeys(columnNames)
        val columns =
          columnNames.mapIndexed { columnIndex, columnName ->
            val columnKey = columnKeys.getValue(columnName)
            LegacyPlanColumn(
              id = stableId("legacy-column:$boardId:$columnKey"),
              boardId = boardId,
              name = columnName,
              nameKey = columnKey,
              rank = rankFor(columnIndex),
            )
          }
        LegacyPlanBoard(
          id = boardId,
          name = boardName,
          nameKey = boardKey,
          rank = rankFor(boardIndex),
          isDefault = boardKey == nameKey(SettingKeys.DEFAULT_BOARD),
          columns = columns,
        )
      }

    val requestedName = validDisplayName(activeBoardName)
    val requestedKey = requestedName?.let(::nameKey)
    val active =
      boards.firstOrNull { it.name == requestedName }
        ?: boards.firstOrNull { nameKey(it.name) == requestedKey }
        ?: boards.firstOrNull { it.isDefault }
        ?: boards.first()
    return LegacyPlanCatalog(boards = boards, activeBoardId = active.id)
  }

  fun nameKey(value: String): String = com.example.data.database.nameKey(value)

  fun stableId(seed: String): String = com.example.data.database.stableId(seed)

  /**
   * Room requires unique normalized keys, but v5 allowed visually similar Unicode names such as
   * `Work` and `Ｗｏｒｋ`. Keep every exact legacy identity and suffix only the colliding keys. The
   * UTF-16 encoding is reversible and collision-free, unlike a truncated hash.
   */
  private fun collisionSafeNameKeys(
    names: List<String>,
    preferredCanonical: String? = null,
  ): Map<String, String> {
    val byBase = names.groupBy(::nameKey)
    val canonicalByBase =
      byBase.mapValues { (_, collisions) ->
        preferredCanonical?.takeIf { it in collisions }
          ?: collisions.minOrNull()
          ?: error("A grouped legacy name set cannot be empty")
      }
    val keysByName =
      canonicalByBase.values.associateWithTo(mutableMapOf()) { canonical -> nameKey(canonical) }
    val reservedNaturalKeys = byBase.keys
    val generatedKeys = mutableSetOf<String>()

    names
      .asSequence()
      .filter { name -> canonicalByBase.getValue(nameKey(name)) != name }
      .sorted()
      .forEach { name ->
        val base = nameKey(name)
        var candidate = "$base#legacy-${utf16Hex(name)}"
        while (candidate in reservedNaturalKeys || !generatedKeys.add(candidate)) {
          candidate += "#"
        }
        keysByName[name] = candidate
      }

    return names.associateWith { name ->
      keysByName.getValue(name)
    }
  }

  private fun utf16Hex(value: String): String =
    buildString(value.length * 4) {
      value.forEach { character -> append(character.code.toString(16).padStart(4, '0')) }
    }

  /** Preserve the exact stored identity; reject only names that contain no visible content. */
  private fun validDisplayName(value: String?): String? = value?.takeIf(String::isNotBlank)

  private fun rankFor(index: Int): Long = (index + 1L) * RANK_GAP

  private const val RANK_GAP = 1_000_000L
}