package dev.unityinflow.kore.storage.tables

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Row
import io.r2dbc.spi.Statement
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.vendors.DatabaseDialect
import org.jetbrains.exposed.v1.r2dbc.mappers.NoValueContainer
import org.jetbrains.exposed.v1.r2dbc.mappers.PresentValueContainer
import org.jetbrains.exposed.v1.r2dbc.mappers.R2dbcTypeMapping
import org.jetbrains.exposed.v1.r2dbc.mappers.TypeMapper
import org.jetbrains.exposed.v1.r2dbc.mappers.ValueContainer

/**
 * Exposed column type for PostgreSQL JSONB.
 *
 * Values are stored and retrieved as [String] (JSON text).
 * Binding is handled by [JsonbTypeMapper] which wraps strings in [Json.of]
 * so the r2dbc-postgresql driver sends the correct JSONB wire type.
 */
class JsonbColumnType : TextColumnType() {
    override fun sqlType(): String = "jsonb"
}

/**
 * R2DBC [TypeMapper] that intercepts [JsonbColumnType] binds and wraps the
 * [String] value in [Json.of] so r2dbc-postgresql sends the JSONB OID.
 *
 * Priority 1.0 ensures this runs before DefaultTypeMapper (priority 0.01).
 * Exposed passes 1-based indices to setValue; r2dbc Statement.bind() is 0-based,
 * so index - 1 is applied (matching DefaultTypeMapper behaviour).
 *
 * Register via [dev.unityinflow.kore.storage.StorageConfig] at database connect time.
 */
class JsonbTypeMapper : TypeMapper {
    // Higher priority than DefaultTypeMapper (0.01) so this mapper wins for JsonbColumnType
    override val priority: Double = 1.0

    // TypeMapper.columnTypes is a Kotlin val property on the interface
    override val columnTypes: List<kotlin.reflect.KClass<out IColumnType<*>>>
        get() = listOf(JsonbColumnType::class)

    override fun setValue(
        statement: Statement,
        dialect: DatabaseDialect,
        typeMapping: R2dbcTypeMapping,
        columnType: IColumnType<*>,
        value: Any?,
        index: Int,
    ): Boolean {
        if (columnType is JsonbColumnType && value is String) {
            // Exposed passes 1-based index; r2dbc Statement.bind(int, Object) is 0-based
            statement.bind(index - 1, Json.of(value))
            return true
        }
        return false
    }

    override fun <T> getValue(
        row: Row,
        type: Class<T>?,
        index: Int,
        dialect: DatabaseDialect,
        columnType: IColumnType<*>,
    ): ValueContainer<T?> {
        if (columnType is JsonbColumnType) {
            val json = row.get(index, Json::class.java)

            @Suppress("UNCHECKED_CAST")
            val result = json?.asString() as T?
            return if (result != null) PresentValueContainer(result) else NoValueContainer()
        }
        return NoValueContainer()
    }
}

/** Declares a JSONB column on this table, stored and retrieved as a JSON [String]. */
fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
