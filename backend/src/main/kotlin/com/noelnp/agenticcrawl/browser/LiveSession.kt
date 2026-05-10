package com.noelnp.agenticcrawl.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class LiveSession internal constructor(
    private val executor: ExecutorService,
    private val playwright: Playwright,
    private val browser: Browser,
    private val context: BrowserContext,
    private val page: Page,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var closed = false

    fun capture(): PageCapture = onSessionThread {
        val screenshot = page.screenshot(
            Page.ScreenshotOptions()
                .setFullPage(false)
                .setType(com.microsoft.playwright.options.ScreenshotType.PNG),
        )
        val visibleText = page.innerText("body")
        PageCapture(screenshot = screenshot, visibleText = visibleText)
    }

    fun findContainerHtml(values: List<String>): String? = onSessionThread {
        if (values.isEmpty()) return@onSessionThread null
        var loc: Locator = page.locator(ROW_CONTAINER_SELECTOR)
        for (value in values) {
            if (value.isBlank()) continue
            loc = loc.filter(Locator.FilterOptions().setHas(page.getByText(value)))
        }
        val target = loc.last()
        runCatching { target.evaluate("el => el.outerHTML") as? String }
            .onFailure { log.warn("findContainerHtml failed: {}", it.message) }
            .getOrNull()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            executor.submit {
                runCatching { page.close() }
                runCatching { context.close() }
                runCatching { browser.close() }
                runCatching { playwright.close() }
            }.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { log.warn("session close did not complete cleanly: {}", it.message) }
        executor.shutdown()
        if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private fun <T> onSessionThread(block: () -> T): T =
        executor.submit<T>(block).get()

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 10L
        const val ROW_CONTAINER_SELECTOR = "div, article, li, tr, section"
    }
}
