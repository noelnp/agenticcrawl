package com.noelnp.agenticcrawl.browser

data class ClickResult(
    val previousUrl: String,
    val currentUrl: String,
    val urlChanged: Boolean,
    val clicked: Boolean,
)
