package com.noelnp.agenticcrawl.analysis

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

enum class TargetType {
    SINGLE,
    MULTI,
}

enum class ActionType {
    CLICK,
    FILL,
}

data class TargetField(
    val name: String,
    val text: String,
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "kind",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = Target.Data::class, name = "DATA"),
    JsonSubTypes.Type(value = Target.Action::class, name = "ACTION"),
)
sealed class Target {
    data class Data(
        val type: TargetType,
        val fields: List<TargetField>,
    ) : Target()

    data class Action(
        val verb: ActionType,
        val text: String,
    ) : Target()
}
