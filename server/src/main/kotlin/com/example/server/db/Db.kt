package com.example.server.db

import com.example.server.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * One Hikari pool per process, created after Flyway has migrated. The slice's queries are
 * explicit SQL — no ORM, matching the repo's minimalism; the engine stays the only place
 * that decides anything, the database only stores.
 */
object Db {
  lateinit var dataSource: DataSource
    private set

  fun init(config: Config) {
    val hikari =
      HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 8
        // The reconciliation worker's advisory lock must never be held while the pool starves
        // the rest of the service; 5s is also the worker's own lock_timeout.
        connectionTimeout = 5_000
      }
    dataSource = HikariDataSource(hikari)
  }

  fun <T> inTransaction(block: (Connection) -> T): T =
    dataSource.connection.use { conn ->
      conn.autoCommit = false
      try {
        val result = block(conn)
        conn.commit()
        result
      } catch (t: Throwable) {
        conn.rollback()
        throw t
      }
    }

  fun <T> Connection.query(sql: String, params: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
      params.forEachIndexed { i, p -> st.bind(i + 1, p) }
      st.executeQuery().use { rs ->
        buildList {
          while (rs.next()) add(map(rs))
        }
      }
    }

  fun <T> Connection.queryOne(sql: String, params: List<Any?> = emptyList(), map: (ResultSet) -> T): T? =
    query(sql, params, map).singleOrNull()

  fun Connection.execute(sql: String, params: List<Any?> = emptyList()): Int =
    prepareStatement(sql).use { st ->
      params.forEachIndexed { i, p -> st.bind(i + 1, p) }
      st.executeUpdate()
    }

  /**
   * Strings bind as *untyped* parameters: Postgres then resolves the column type by context,
   * so one call site serves both `uuid` columns (workspaces.id, workspace_id everywhere) and
   * `text` columns (the migrated TEXT ids). Lists bind as `text[]` for `= ANY (?)`.
   */
  private fun java.sql.PreparedStatement.bind(index: Int, value: Any?) {
    when (value) {
      is String -> setObject(index, value, java.sql.Types.OTHER)
      is List<*> -> setArray(index, connection.createArrayOf("text", value.toTypedArray()))
      else -> setObject(index, value)
    }
  }

  fun newId(): String = UUID.randomUUID().toString()
}