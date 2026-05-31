package org.tekfive.ack.sources.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseSourceTest {

    private fun mockConnection(rows: List<Pair<String?, String?>>): () -> Connection {
        return {
            val rs = MockResultSet(rows)
            val stmt = MockStatement(rs)
            MockConnection(stmt)
        }
    }

    /** Rows are (name, value, namespace) — the column order the source SELECTs in namespace mode. */
    private fun mockNamespacedConnection(rows: List<Triple<String?, String?, String?>>): () -> Connection {
        return {
            MockConnection(MockStatement(MockResultSet3(rows)))
        }
    }

    @Test
    fun `loads properties from database on first get`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf("APP_NAME" to "ClinicAIde", "PORT" to "8080")),
        )

        assertEquals("ClinicAIde", source.get("APP_NAME"))
        assertEquals("8080", source.get("PORT"))
    }

    @Test
    fun `returns null for unknown property`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf("APP_NAME" to "ClinicAIde")),
        )

        assertNull(source.get("UNKNOWN"))
    }

    @Test
    fun `caches results and does not query again when ttl is null`() {
        var queryCount = 0
        val source = DatabaseSource(
            connectionProvider = {
                queryCount++
                mockConnection(listOf("KEY" to "value"))()
            },
        )

        source.get("KEY")
        source.get("KEY")
        source.get("OTHER")

        assertEquals(1, queryCount)
    }

    @Test
    fun `reloads when ttl expires`() {
        var queryCount = 0
        var currentValue = "first"
        val source = DatabaseSource(
            connectionProvider = {
                queryCount++
                mockConnection(listOf("KEY" to currentValue))()
            },
            ttlSeconds = 0, // expires immediately
        )

        assertEquals("first", source.get("KEY"))
        assertEquals(1, queryCount)

        currentValue = "second"
        Thread.sleep(2)
        assertEquals("second", source.get("KEY"))
        assertEquals(2, queryCount)
    }

    @Test
    fun `empty table returns null for all keys`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(emptyList()),
        )

        assertNull(source.get("ANYTHING"))
    }

    @Test
    fun `skips rows with null name`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf(null to "value", "KEY" to "value")),
        )

        assertNull(source.get(null.toString()))
        assertEquals("value", source.get("KEY"))
    }

    @Test
    fun `skips rows with null value`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf("KEY" to null, "OTHER" to "present")),
        )

        assertNull(source.get("KEY"))
        assertEquals("present", source.get("OTHER"))
    }

    @Test
    fun `returns null when connection fails and no prior cache`() {
        val source = DatabaseSource(
            connectionProvider = { throw SQLException("connection refused") },
        )

        assertNull(source.get("KEY"))
    }

    @Test
    fun `retries after initial load failure when no cache exists`() {
        var shouldFail = true
        var queryCount = 0
        val source = DatabaseSource(
            connectionProvider = {
                queryCount++
                if (shouldFail) throw SQLException("connection refused")
                mockConnection(listOf("KEY" to "recovered"))()
            },
        )

        assertNull(source.get("KEY"))

        shouldFail = false
        assertEquals("recovered", source.get("KEY"))
        assertEquals(3, queryCount)
    }

    @Test
    fun `uses stale cache when reload fails`() {
        var shouldFail = false
        val source = DatabaseSource(
            connectionProvider = {
                if (shouldFail) throw SQLException("connection refused")
                mockConnection(listOf("KEY" to "cached"))()
            },
            ttlSeconds = 0,
        )

        assertEquals("cached", source.get("KEY"))

        shouldFail = true
        assertEquals("cached", source.get("KEY"))
    }

    @Test
    fun `clearCache forces reload on next access`() {
        var queryCount = 0
        val source = DatabaseSource(
            connectionProvider = {
                queryCount++
                mockConnection(listOf("KEY" to "value"))()
            },
        )

        source.get("KEY")
        assertEquals(1, queryCount)

        source.clearCache()
        source.get("KEY")
        assertEquals(2, queryCount)
    }

    @Test
    fun `rejects invalid table name`() {
        assertThrows<IllegalArgumentException> {
            DatabaseSource(connectionProvider = { throw AssertionError() }, tableName = "DROP TABLE x; --")
        }
    }

    @Test
    fun `rejects invalid column name`() {
        assertThrows<IllegalArgumentException> {
            DatabaseSource(connectionProvider = { throw AssertionError() }, nameColumn = "1bad")
        }
    }

    @Test
    fun `accepts valid identifiers with underscores and digits`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf("K" to "V")),
            tableName = "app_config_2",
            nameColumn = "config_key",
            valueColumn = "config_value",
        )

        assertEquals("V", source.get("K"))
    }

    @Test
    fun `matches namespaced and un-namespaced rows by (namespace, name)`() {
        val source = DatabaseSource(
            connectionProvider = mockNamespacedConnection(
                listOf(
                    Triple("POLL", "5", "FSW"),
                    Triple("POLL", "9", null),
                ),
            ),
        )

        assertEquals("5", source.get("POLL", "FSW"))
        assertEquals("9", source.get("POLL", null))
        assertEquals("9", source.get("POLL"))
        assertNull(source.get("POLL", "OTHER"))
    }

    @Test
    fun `flat mode ignores namespace column and treats all rows as un-namespaced`() {
        val source = DatabaseSource(
            connectionProvider = mockConnection(listOf("POLL" to "9")),
            namespaceColumn = null,
        )

        assertEquals("9", source.get("POLL"))
        assertNull(source.get("POLL", "FSW"))
    }

    // --- Mock JDBC classes ---

    private class MockResultSet(private val rows: List<Pair<String?, String?>>) : ResultSetStub() {
        private var index = -1

        override fun next(): Boolean {
            index++
            return index < rows.size
        }

        override fun getString(columnIndex: Int): String? {
            return when (columnIndex) {
                1 -> rows[index].first
                2 -> rows[index].second
                else -> null
            }
        }

        override fun close() {}
    }

    private class MockResultSet3(private val rows: List<Triple<String?, String?, String?>>) : ResultSetStub() {
        private var index = -1

        override fun next(): Boolean {
            index++
            return index < rows.size
        }

        override fun getString(columnIndex: Int): String? {
            return when (columnIndex) {
                1 -> rows[index].first
                2 -> rows[index].second
                3 -> rows[index].third
                else -> null
            }
        }

        override fun close() {}
    }

    private class MockStatement(private val rs: ResultSet) : StatementStub() {
        override fun executeQuery(sql: String): ResultSet = rs
        override fun close() {}
    }

    private class MockConnection(private val stmt: Statement) : ConnectionStub() {
        override fun createStatement(): Statement = stmt
        override fun close() {}
    }

    /** Stub that throws for all unimplemented ResultSet methods. */
    @Suppress("OVERRIDE_DEPRECATION")
    private abstract class ResultSetStub : ResultSet {
        override fun absolute(row: Int): Boolean = throw UnsupportedOperationException()
        override fun afterLast() = throw UnsupportedOperationException()
        override fun beforeFirst() = throw UnsupportedOperationException()
        override fun cancelRowUpdates() = throw UnsupportedOperationException()
        override fun clearWarnings() = throw UnsupportedOperationException()
        override fun deleteRow() = throw UnsupportedOperationException()
        override fun findColumn(columnLabel: String?): Int = throw UnsupportedOperationException()
        override fun first(): Boolean = throw UnsupportedOperationException()
        override fun getArray(columnIndex: Int): java.sql.Array = throw UnsupportedOperationException()
        override fun getArray(columnLabel: String?): java.sql.Array = throw UnsupportedOperationException()
        override fun getAsciiStream(columnIndex: Int) = throw UnsupportedOperationException()
        override fun getAsciiStream(columnLabel: String?) = throw UnsupportedOperationException()
        override fun getBigDecimal(columnIndex: Int) = throw UnsupportedOperationException()
        override fun getBigDecimal(columnLabel: String?) = throw UnsupportedOperationException()
        @Suppress("DEPRECATION") override fun getBigDecimal(columnIndex: Int, scale: Int) = throw UnsupportedOperationException()
        @Suppress("DEPRECATION") override fun getBigDecimal(columnLabel: String?, scale: Int) = throw UnsupportedOperationException()
        override fun getBinaryStream(columnIndex: Int) = throw UnsupportedOperationException()
        override fun getBinaryStream(columnLabel: String?) = throw UnsupportedOperationException()
        override fun getBlob(columnIndex: Int): Blob = throw UnsupportedOperationException()
        override fun getBlob(columnLabel: String?): Blob = throw UnsupportedOperationException()
        override fun getBoolean(columnIndex: Int): Boolean = throw UnsupportedOperationException()
        override fun getBoolean(columnLabel: String?): Boolean = throw UnsupportedOperationException()
        override fun getByte(columnIndex: Int): Byte = throw UnsupportedOperationException()
        override fun getByte(columnLabel: String?): Byte = throw UnsupportedOperationException()
        override fun getBytes(columnIndex: Int): ByteArray = throw UnsupportedOperationException()
        override fun getBytes(columnLabel: String?): ByteArray = throw UnsupportedOperationException()
        override fun getCharacterStream(columnIndex: Int) = throw UnsupportedOperationException()
        override fun getCharacterStream(columnLabel: String?) = throw UnsupportedOperationException()
        override fun getClob(columnIndex: Int): Clob = throw UnsupportedOperationException()
        override fun getClob(columnLabel: String?): Clob = throw UnsupportedOperationException()
        override fun getConcurrency(): Int = throw UnsupportedOperationException()
        override fun getCursorName(): String = throw UnsupportedOperationException()
        override fun getDate(columnIndex: Int): java.sql.Date = throw UnsupportedOperationException()
        override fun getDate(columnLabel: String?): java.sql.Date = throw UnsupportedOperationException()
        override fun getDate(columnIndex: Int, cal: java.util.Calendar?): java.sql.Date = throw UnsupportedOperationException()
        override fun getDate(columnLabel: String?, cal: java.util.Calendar?): java.sql.Date = throw UnsupportedOperationException()
        override fun getDouble(columnIndex: Int): Double = throw UnsupportedOperationException()
        override fun getDouble(columnLabel: String?): Double = throw UnsupportedOperationException()
        override fun getFetchDirection(): Int = throw UnsupportedOperationException()
        override fun getFetchSize(): Int = throw UnsupportedOperationException()
        override fun getFloat(columnIndex: Int): Float = throw UnsupportedOperationException()
        override fun getFloat(columnLabel: String?): Float = throw UnsupportedOperationException()
        override fun getHoldability(): Int = throw UnsupportedOperationException()
        override fun getInt(columnIndex: Int): Int = throw UnsupportedOperationException()
        override fun getInt(columnLabel: String?): Int = throw UnsupportedOperationException()
        override fun getLong(columnIndex: Int): Long = throw UnsupportedOperationException()
        override fun getLong(columnLabel: String?): Long = throw UnsupportedOperationException()
        override fun getMetaData(): ResultSetMetaData = throw UnsupportedOperationException()
        override fun getNCharacterStream(columnIndex: Int) = throw UnsupportedOperationException()
        override fun getNCharacterStream(columnLabel: String?) = throw UnsupportedOperationException()
        override fun getNClob(columnIndex: Int): NClob = throw UnsupportedOperationException()
        override fun getNClob(columnLabel: String?): NClob = throw UnsupportedOperationException()
        override fun getNString(columnIndex: Int): String = throw UnsupportedOperationException()
        override fun getNString(columnLabel: String?): String = throw UnsupportedOperationException()
        override fun getObject(columnIndex: Int): Any = throw UnsupportedOperationException()
        override fun getObject(columnLabel: String?): Any = throw UnsupportedOperationException()
        override fun getObject(columnIndex: Int, map: MutableMap<String, Class<*>>?): Any = throw UnsupportedOperationException()
        override fun getObject(columnLabel: String?, map: MutableMap<String, Class<*>>?): Any = throw UnsupportedOperationException()
        override fun <T : Any?> getObject(columnIndex: Int, type: Class<T>?): T = throw UnsupportedOperationException()
        override fun <T : Any?> getObject(columnLabel: String?, type: Class<T>?): T = throw UnsupportedOperationException()
        override fun getRef(columnIndex: Int): Ref = throw UnsupportedOperationException()
        override fun getRef(columnLabel: String?): Ref = throw UnsupportedOperationException()
        override fun getRow(): Int = throw UnsupportedOperationException()
        override fun getRowId(columnIndex: Int): RowId = throw UnsupportedOperationException()
        override fun getRowId(columnLabel: String?): RowId = throw UnsupportedOperationException()
        override fun getSQLXML(columnIndex: Int): SQLXML = throw UnsupportedOperationException()
        override fun getSQLXML(columnLabel: String?): SQLXML = throw UnsupportedOperationException()
        override fun getShort(columnIndex: Int): Short = throw UnsupportedOperationException()
        override fun getShort(columnLabel: String?): Short = throw UnsupportedOperationException()
        override fun getStatement(): Statement = throw UnsupportedOperationException()
        override fun getString(columnLabel: String?): String = throw UnsupportedOperationException()
        override fun getTime(columnIndex: Int): Time = throw UnsupportedOperationException()
        override fun getTime(columnLabel: String?): Time = throw UnsupportedOperationException()
        override fun getTime(columnIndex: Int, cal: java.util.Calendar?): Time = throw UnsupportedOperationException()
        override fun getTime(columnLabel: String?, cal: java.util.Calendar?): Time = throw UnsupportedOperationException()
        override fun getTimestamp(columnIndex: Int): Timestamp = throw UnsupportedOperationException()
        override fun getTimestamp(columnLabel: String?): Timestamp = throw UnsupportedOperationException()
        override fun getTimestamp(columnIndex: Int, cal: java.util.Calendar?): Timestamp = throw UnsupportedOperationException()
        override fun getTimestamp(columnLabel: String?, cal: java.util.Calendar?): Timestamp = throw UnsupportedOperationException()
        override fun getType(): Int = throw UnsupportedOperationException()
        override fun getURL(columnIndex: Int): java.net.URL = throw UnsupportedOperationException()
        override fun getURL(columnLabel: String?): java.net.URL = throw UnsupportedOperationException()
        @Suppress("DEPRECATION") override fun getUnicodeStream(columnIndex: Int) = throw UnsupportedOperationException()
        @Suppress("DEPRECATION") override fun getUnicodeStream(columnLabel: String?) = throw UnsupportedOperationException()
        override fun getWarnings(): SQLWarning = throw UnsupportedOperationException()
        override fun insertRow() = throw UnsupportedOperationException()
        override fun isAfterLast(): Boolean = throw UnsupportedOperationException()
        override fun isBeforeFirst(): Boolean = throw UnsupportedOperationException()
        override fun isClosed(): Boolean = throw UnsupportedOperationException()
        override fun isFirst(): Boolean = throw UnsupportedOperationException()
        override fun isLast(): Boolean = throw UnsupportedOperationException()
        override fun last(): Boolean = throw UnsupportedOperationException()
        override fun moveToCurrentRow() = throw UnsupportedOperationException()
        override fun moveToInsertRow() = throw UnsupportedOperationException()
        override fun previous(): Boolean = throw UnsupportedOperationException()
        override fun refreshRow() = throw UnsupportedOperationException()
        override fun relative(rows: Int): Boolean = throw UnsupportedOperationException()
        override fun rowDeleted(): Boolean = throw UnsupportedOperationException()
        override fun rowInserted(): Boolean = throw UnsupportedOperationException()
        override fun rowUpdated(): Boolean = throw UnsupportedOperationException()
        override fun setFetchDirection(direction: Int) = throw UnsupportedOperationException()
        override fun setFetchSize(rows: Int) = throw UnsupportedOperationException()
        override fun updateArray(columnIndex: Int, x: java.sql.Array?) = throw UnsupportedOperationException()
        override fun updateArray(columnLabel: String?, x: java.sql.Array?) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Int) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?, length: Int) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnIndex: Int, x: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateAsciiStream(columnLabel: String?, x: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateBigDecimal(columnIndex: Int, x: java.math.BigDecimal?) = throw UnsupportedOperationException()
        override fun updateBigDecimal(columnLabel: String?, x: java.math.BigDecimal?) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Int) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?, length: Int) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnIndex: Int, x: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateBinaryStream(columnLabel: String?, x: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateBlob(columnIndex: Int, x: Blob?) = throw UnsupportedOperationException()
        override fun updateBlob(columnLabel: String?, x: Blob?) = throw UnsupportedOperationException()
        override fun updateBlob(columnIndex: Int, inputStream: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateBlob(columnLabel: String?, inputStream: java.io.InputStream?, length: Long) = throw UnsupportedOperationException()
        override fun updateBlob(columnIndex: Int, inputStream: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateBlob(columnLabel: String?, inputStream: java.io.InputStream?) = throw UnsupportedOperationException()
        override fun updateBoolean(columnIndex: Int, x: Boolean) = throw UnsupportedOperationException()
        override fun updateBoolean(columnLabel: String?, x: Boolean) = throw UnsupportedOperationException()
        override fun updateByte(columnIndex: Int, x: Byte) = throw UnsupportedOperationException()
        override fun updateByte(columnLabel: String?, x: Byte) = throw UnsupportedOperationException()
        override fun updateBytes(columnIndex: Int, x: ByteArray?) = throw UnsupportedOperationException()
        override fun updateBytes(columnLabel: String?, x: ByteArray?) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Int) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnLabel: String?, reader: java.io.Reader?, length: Int) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnLabel: String?, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnIndex: Int, x: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateCharacterStream(columnLabel: String?, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateClob(columnIndex: Int, x: Clob?) = throw UnsupportedOperationException()
        override fun updateClob(columnLabel: String?, x: Clob?) = throw UnsupportedOperationException()
        override fun updateClob(columnIndex: Int, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateClob(columnLabel: String?, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateClob(columnIndex: Int, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateClob(columnLabel: String?, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateDate(columnIndex: Int, x: java.sql.Date?) = throw UnsupportedOperationException()
        override fun updateDate(columnLabel: String?, x: java.sql.Date?) = throw UnsupportedOperationException()
        override fun updateDouble(columnIndex: Int, x: Double) = throw UnsupportedOperationException()
        override fun updateDouble(columnLabel: String?, x: Double) = throw UnsupportedOperationException()
        override fun updateFloat(columnIndex: Int, x: Float) = throw UnsupportedOperationException()
        override fun updateFloat(columnLabel: String?, x: Float) = throw UnsupportedOperationException()
        override fun updateInt(columnIndex: Int, x: Int) = throw UnsupportedOperationException()
        override fun updateInt(columnLabel: String?, x: Int) = throw UnsupportedOperationException()
        override fun updateLong(columnIndex: Int, x: Long) = throw UnsupportedOperationException()
        override fun updateLong(columnLabel: String?, x: Long) = throw UnsupportedOperationException()
        override fun updateNCharacterStream(columnIndex: Int, x: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateNCharacterStream(columnLabel: String?, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateNCharacterStream(columnIndex: Int, x: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateNCharacterStream(columnLabel: String?, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateNClob(columnIndex: Int, nClob: NClob?) = throw UnsupportedOperationException()
        override fun updateNClob(columnLabel: String?, nClob: NClob?) = throw UnsupportedOperationException()
        override fun updateNClob(columnIndex: Int, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateNClob(columnLabel: String?, reader: java.io.Reader?, length: Long) = throw UnsupportedOperationException()
        override fun updateNClob(columnIndex: Int, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateNClob(columnLabel: String?, reader: java.io.Reader?) = throw UnsupportedOperationException()
        override fun updateNString(columnIndex: Int, nString: String?) = throw UnsupportedOperationException()
        override fun updateNString(columnLabel: String?, nString: String?) = throw UnsupportedOperationException()
        override fun updateNull(columnIndex: Int) = throw UnsupportedOperationException()
        override fun updateNull(columnLabel: String?) = throw UnsupportedOperationException()
        override fun updateObject(columnIndex: Int, x: Any?, scaleOrLength: Int) = throw UnsupportedOperationException()
        override fun updateObject(columnIndex: Int, x: Any?) = throw UnsupportedOperationException()
        override fun updateObject(columnLabel: String?, x: Any?, scaleOrLength: Int) = throw UnsupportedOperationException()
        override fun updateObject(columnLabel: String?, x: Any?) = throw UnsupportedOperationException()
        override fun updateRef(columnIndex: Int, x: Ref?) = throw UnsupportedOperationException()
        override fun updateRef(columnLabel: String?, x: Ref?) = throw UnsupportedOperationException()
        override fun updateRow() = throw UnsupportedOperationException()
        override fun updateRowId(columnIndex: Int, x: RowId?) = throw UnsupportedOperationException()
        override fun updateRowId(columnLabel: String?, x: RowId?) = throw UnsupportedOperationException()
        override fun updateSQLXML(columnIndex: Int, xmlObject: SQLXML?) = throw UnsupportedOperationException()
        override fun updateSQLXML(columnLabel: String?, xmlObject: SQLXML?) = throw UnsupportedOperationException()
        override fun updateShort(columnIndex: Int, x: Short) = throw UnsupportedOperationException()
        override fun updateShort(columnLabel: String?, x: Short) = throw UnsupportedOperationException()
        override fun updateString(columnIndex: Int, x: String?) = throw UnsupportedOperationException()
        override fun updateString(columnLabel: String?, x: String?) = throw UnsupportedOperationException()
        override fun updateTime(columnIndex: Int, x: Time?) = throw UnsupportedOperationException()
        override fun updateTime(columnLabel: String?, x: Time?) = throw UnsupportedOperationException()
        override fun updateTimestamp(columnIndex: Int, x: Timestamp?) = throw UnsupportedOperationException()
        override fun updateTimestamp(columnLabel: String?, x: Timestamp?) = throw UnsupportedOperationException()
        override fun wasNull(): Boolean = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    }

    /** Stub that throws for all unimplemented Statement methods. */
    private abstract class StatementStub : Statement {
        override fun addBatch(sql: String?) = throw UnsupportedOperationException()
        override fun cancel() = throw UnsupportedOperationException()
        override fun clearBatch() = throw UnsupportedOperationException()
        override fun clearWarnings() = throw UnsupportedOperationException()
        override fun execute(sql: String?): Boolean = throw UnsupportedOperationException()
        override fun execute(sql: String?, autoGeneratedKeys: Int): Boolean = throw UnsupportedOperationException()
        override fun execute(sql: String?, columnIndexes: IntArray?): Boolean = throw UnsupportedOperationException()
        override fun execute(sql: String?, columnNames: Array<out String>?): Boolean = throw UnsupportedOperationException()
        override fun executeBatch(): IntArray = throw UnsupportedOperationException()
        override fun executeLargeUpdate(sql: String?): Long = throw UnsupportedOperationException()
        override fun executeLargeUpdate(sql: String?, autoGeneratedKeys: Int): Long = throw UnsupportedOperationException()
        override fun executeLargeUpdate(sql: String?, columnIndexes: IntArray?): Long = throw UnsupportedOperationException()
        override fun executeLargeUpdate(sql: String?, columnNames: Array<out String>?): Long = throw UnsupportedOperationException()
        override fun executeUpdate(sql: String?): Int = throw UnsupportedOperationException()
        override fun executeUpdate(sql: String?, autoGeneratedKeys: Int): Int = throw UnsupportedOperationException()
        override fun executeUpdate(sql: String?, columnIndexes: IntArray?): Int = throw UnsupportedOperationException()
        override fun executeUpdate(sql: String?, columnNames: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun getConnection(): Connection = throw UnsupportedOperationException()
        override fun getFetchDirection(): Int = throw UnsupportedOperationException()
        override fun getFetchSize(): Int = throw UnsupportedOperationException()
        override fun getGeneratedKeys(): ResultSet = throw UnsupportedOperationException()
        override fun getLargeMaxRows(): Long = throw UnsupportedOperationException()
        override fun getLargeUpdateCount(): Long = throw UnsupportedOperationException()
        override fun getMaxFieldSize(): Int = throw UnsupportedOperationException()
        override fun getMaxRows(): Int = throw UnsupportedOperationException()
        override fun getMoreResults(): Boolean = throw UnsupportedOperationException()
        override fun getMoreResults(current: Int): Boolean = throw UnsupportedOperationException()
        override fun getQueryTimeout(): Int = throw UnsupportedOperationException()
        override fun getResultSet(): ResultSet = throw UnsupportedOperationException()
        override fun getResultSetConcurrency(): Int = throw UnsupportedOperationException()
        override fun getResultSetHoldability(): Int = throw UnsupportedOperationException()
        override fun getResultSetType(): Int = throw UnsupportedOperationException()
        override fun getUpdateCount(): Int = throw UnsupportedOperationException()
        override fun getWarnings(): SQLWarning = throw UnsupportedOperationException()
        override fun isClosed(): Boolean = throw UnsupportedOperationException()
        override fun isCloseOnCompletion(): Boolean = throw UnsupportedOperationException()
        override fun isPoolable(): Boolean = throw UnsupportedOperationException()
        override fun closeOnCompletion() = throw UnsupportedOperationException()
        override fun setFetchDirection(direction: Int) = throw UnsupportedOperationException()
        override fun setFetchSize(rows: Int) = throw UnsupportedOperationException()
        override fun setCursorName(name: String?) = throw UnsupportedOperationException()
        override fun setEscapeProcessing(enable: Boolean) = throw UnsupportedOperationException()
        override fun setLargeMaxRows(max: Long) = throw UnsupportedOperationException()
        override fun setMaxFieldSize(max: Int) = throw UnsupportedOperationException()
        override fun setMaxRows(max: Int) = throw UnsupportedOperationException()
        override fun setPoolable(poolable: Boolean) = throw UnsupportedOperationException()
        override fun setQueryTimeout(seconds: Int) = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    }

    /** Stub that throws for all unimplemented Connection methods. */
    private abstract class ConnectionStub : Connection {
        override fun abort(executor: java.util.concurrent.Executor?) = throw UnsupportedOperationException()
        override fun clearWarnings() = throw UnsupportedOperationException()
        override fun commit() = throw UnsupportedOperationException()
        override fun createArrayOf(typeName: String?, elements: Array<out Any>?): java.sql.Array = throw UnsupportedOperationException()
        override fun createBlob(): Blob = throw UnsupportedOperationException()
        override fun createClob(): Clob = throw UnsupportedOperationException()
        override fun createNClob(): NClob = throw UnsupportedOperationException()
        override fun createSQLXML(): SQLXML = throw UnsupportedOperationException()
        override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement = throw UnsupportedOperationException()
        override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement = throw UnsupportedOperationException()
        override fun createStruct(typeName: String?, attributes: Array<out Any>?): Struct = throw UnsupportedOperationException()
        override fun getAutoCommit(): Boolean = throw UnsupportedOperationException()
        override fun getCatalog(): String = throw UnsupportedOperationException()
        override fun getClientInfo(): java.util.Properties = throw UnsupportedOperationException()
        override fun getClientInfo(name: String?): String = throw UnsupportedOperationException()
        override fun getHoldability(): Int = throw UnsupportedOperationException()
        override fun getMetaData(): DatabaseMetaData = throw UnsupportedOperationException()
        override fun getNetworkTimeout(): Int = throw UnsupportedOperationException()
        override fun getSchema(): String = throw UnsupportedOperationException()
        override fun getTransactionIsolation(): Int = throw UnsupportedOperationException()
        override fun getTypeMap(): MutableMap<String, Class<*>> = throw UnsupportedOperationException()
        override fun getWarnings(): SQLWarning = throw UnsupportedOperationException()
        override fun isClosed(): Boolean = throw UnsupportedOperationException()
        override fun isReadOnly(): Boolean = throw UnsupportedOperationException()
        override fun isValid(timeout: Int): Boolean = throw UnsupportedOperationException()
        override fun nativeSQL(sql: String?): String = throw UnsupportedOperationException()
        override fun prepareCall(sql: String?): CallableStatement = throw UnsupportedOperationException()
        override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int): CallableStatement = throw UnsupportedOperationException()
        override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): CallableStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?): PreparedStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?, autoGeneratedKeys: Int): PreparedStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?, columnIndexes: IntArray?): PreparedStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?, columnNames: Array<out String>?): PreparedStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement = throw UnsupportedOperationException()
        override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): PreparedStatement = throw UnsupportedOperationException()
        override fun releaseSavepoint(savepoint: Savepoint?) = throw UnsupportedOperationException()
        override fun rollback() = throw UnsupportedOperationException()
        override fun rollback(savepoint: Savepoint?) = throw UnsupportedOperationException()
        override fun setAutoCommit(autoCommit: Boolean) = throw UnsupportedOperationException()
        override fun setCatalog(catalog: String?) = throw UnsupportedOperationException()
        override fun setClientInfo(name: String?, value: String?) = throw UnsupportedOperationException()
        override fun setClientInfo(properties: java.util.Properties?) = throw UnsupportedOperationException()
        override fun setHoldability(holdability: Int) = throw UnsupportedOperationException()
        override fun setNetworkTimeout(executor: java.util.concurrent.Executor?, milliseconds: Int) = throw UnsupportedOperationException()
        override fun setReadOnly(readOnly: Boolean) = throw UnsupportedOperationException()
        override fun setSavepoint(): Savepoint = throw UnsupportedOperationException()
        override fun setSavepoint(name: String?): Savepoint = throw UnsupportedOperationException()
        override fun setSchema(schema: String?) = throw UnsupportedOperationException()
        override fun setTransactionIsolation(level: Int) = throw UnsupportedOperationException()
        override fun setTypeMap(map: MutableMap<String, Class<*>>?) = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
    }
}
