package com.noelnp.agenticcrawl.browser.model

data class ClickResult(
    val previousUrl: String,
    val currentUrl: String,
    val urlChanged: Boolean,
    val clicked: Boolean,
)
