package com.noelnp.agenticcrawl.browser

data class PageCapture(
    val screenshot: ByteArray,
    val visibleText: String,
)
