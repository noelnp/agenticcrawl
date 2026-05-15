package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "from",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ValueSource.Text::class, name = "TEXT"),
    JsonSubTypes.Type(value = ValueSource.Attribute::class, name = "ATTRIBUTE"),
)
sealed class ValueSource {
    data object Text : ValueSource()
    data class Attribute(val name: String) : ValueSource()
}

data class FieldSelector(
    val name: String,
    val selector: String,
    val source: ValueSource,
    val nth: Int? = null,
)

data class ExtractedStructure(
    val rowSelector: String,
    val fields: List<FieldSelector>,
)
