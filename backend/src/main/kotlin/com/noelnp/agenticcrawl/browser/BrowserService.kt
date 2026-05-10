package com.noelnp.agenticcrawl.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ViewportSize
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class BrowserService(private val consentDismisser: ConsentDismisser) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun openSession(url: String): LiveSession {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "playwright-session").apply { isDaemon = true }
        }

        return try {
            executor.submit<LiveSession> {
                log.debug("launching headless chromium")
                val playwright = Playwright.create()
                val browser = playwright.chromium()
                    .launch(BrowserType.LaunchOptions().setHeadless(true))
                val context = browser.newContext(viewportContext())
                val page = context.newPage()

                log.info("navigating url={} viewport={}x{}", url, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                page.navigate(url)

                log.debug("waiting for load event")
                page.waitForLoadState(LoadState.LOAD)
                log.debug("load event reached, settling {}ms", POST_LOAD_SETTLE_MS.toInt())
                page.waitForTimeout(POST_LOAD_SETTLE_MS)

                val dismissed = consentDismisser.dismiss(page)
                if (dismissed.isNotEmpty()) {
                    log.debug("post-dismiss settle {}ms", POST_DISMISS_SETTLE_MS.toInt())
                    page.waitForTimeout(POST_DISMISS_SETTLE_MS)
                }

                LiveSession(executor, playwright, browser, context, page)
            }.get()
        } catch (e: Exception) {
            executor.shutdownNow()
            throw e
        }
    }

    private fun viewportContext(): Browser.NewContextOptions =
        Browser.NewContextOptions()
            .setViewportSize(ViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
            .setDeviceScaleFactor(1.0)

    private companion object {
        const val VIEWPORT_WIDTH = 1440
        const val VIEWPORT_HEIGHT = 1600
        const val POST_LOAD_SETTLE_MS = 1500.0
        const val POST_DISMISS_SETTLE_MS = 300.0
    }
}
