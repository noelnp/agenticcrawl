package com.noelnp.agenticcrawl.analysis

enum class TargetType {
    SINGLE,
    MULTI,
}

data class TargetField(
    val name: String,
    val text: String,
)

data class Target(
    val type: TargetType,
    val fields: List<TargetField>,
)
