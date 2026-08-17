package com.example.data.backup

/**
 * A typed row of a backed-up table. Room cursors hand back exactly three shapes of value, so
 * the codec deals in those three and null, and nothing else can appear in a whitelisted table.
 */
sealed interface BackupValue {
  data class Text(val value: String) : BackupValue
  data class Int64(val value: Long) : BackupValue
  data class Real(val value: Double) : BackupValue
  data object Null : BackupValue
}

/**
 * Serializes whole-table row dumps into one text document and back, losslessly.
 *
 * The format is line based: a magic header, a version, then one table section per backed-up
 * table, each a tab-separated row per line with escaped field values. A malformed document
 * throws rather than returning a half-decoded backup whose restore would silently drop columns.
 */
object RowBackupCodec {
  const val MAGIC = "dailybrief-backup"
  const val VERSION = 1

  fun encode(rows: Map<String, List<Map<String, BackupValue>>>): String =
    buildString {
      appendLine(MAGIC)
      appendLine(VERSION.toString())
      rows.forEach { (table, tableRows) ->
        append(table)
        append('\t')
        append(tableRows.size)
        append('\n')
        tableRows.forEach { row -> appendRow(this, row) }
      }
    }

  fun decode(text: String): Map<String, List<Map<String, BackupValue>>> {
    val lines = text.split('\n')
    require(lines.isNotEmpty() && lines[0] == MAGIC) { "Not a Daily Brief backup" }
    require(lines.size >= 2 && lines[1].toIntOrNull() == VERSION) {
      "Unsupported backup version"
    }
    val result = LinkedHashMap<String, List<Map<String, BackupValue>>>()
    var index = 2
    while (index < lines.size && lines[index].isNotEmpty()) {
      val header = lines[index].split('\t')
      require(header.size == 2) { "Malformed table header at line ${index + 1}" }
      val table = header[0]
      val rowCount = header[1].toIntOrNull() ?: throw IllegalArgumentException("Bad row count")
      val tableRows = mutableListOf<Map<String, BackupValue>>()
      repeat(rowCount) {
        index++
        require(index < lines.size) { "Truncated backup inside table $table" }
        tableRows += decodeRow(lines[index])
      }
      result[table] = tableRows
      index++
    }
    return result
  }

  private fun appendRow(builder: StringBuilder, row: Map<String, BackupValue>) {
    row.entries.forEachIndexed { position, (name, value) ->
      builder.append(name)
      builder.append('\t')
      when (value) {
        is BackupValue.Text -> {
          builder.append('s')
          builder.append('\t')
          builder.append(escape(value.value))
        }
        is BackupValue.Int64 -> {
          builder.append('i')
          builder.append('\t')
          builder.append(value.value)
        }
        is BackupValue.Real -> {
          builder.append('d')
          builder.append('\t')
          builder.append(value.value)
        }
        BackupValue.Null -> {
          builder.append('n')
          builder.append('\t')
          builder.append("")
        }
      }
      builder.append(if (position == row.size - 1) '\n' else '\t')
    }
  }

  private fun decodeRow(line: String): Map<String, BackupValue> {
    val tokens = line.split('\t')
    require(tokens.size % 3 == 0) { "Malformed row" }
    val row = LinkedHashMap<String, BackupValue>()
    var cursor = 0
    while (cursor < tokens.size) {
      val name = tokens[cursor]
      val type = tokens[cursor + 1]
      val raw = tokens[cursor + 2]
      row[name] =
        when (type) {
          "s" -> BackupValue.Text(unescape(raw))
          "i" -> BackupValue.Int64(raw.toLongOrNull() ?: throw IllegalArgumentException("Bad long in $name"))
          "d" -> BackupValue.Real(raw.toDoubleOrNull() ?: throw IllegalArgumentException("Bad double in $name"))
          "n" -> BackupValue.Null
          else -> throw IllegalArgumentException("Unknown field type '$type'")
        }
      cursor += 3
    }
    return row
  }

  private fun escape(value: String): String =
    buildString(value.length + 8) {
      value.forEach { char ->
        when (char) {
          '\\' -> append("\\\\")
          '\n' -> append("\\n")
          '\t' -> append("\\t")
          else -> append(char)
        }
      }
    }

  private fun unescape(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
      val char = value[index]
      if (char == '\\' && index + 1 < value.length) {
        when (value[index + 1]) {
          'n' -> {
            result.append('\n')
            index += 2
            continue
          }
          't' -> {
            result.append('\t')
            index += 2
            continue
          }
          '\\' -> {
            result.append('\\')
            index += 2
            continue
          }
        }
      }
      result.append(char)
      index++
    }
    return result.toString()
  }
}
