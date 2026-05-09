package com.noelnp.agenticcrawl.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import com.microsoft.playwright.options.ViewportSize
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BrowserService(private val consentDismisser: ConsentDismisser) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun capture(url: String): PageCapture =
        Playwright.create().use { playwright ->
            log.debug("launching headless chromium")
            playwright.chromium()
                .launch(BrowserType.LaunchOptions().setHeadless(true))
                .use { browser ->
                    browser.newContext(viewportContext()).use { context ->
                        context.newPage().use { page ->
                            log.info("navigating url={} viewport={}x{}", url, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                            page.navigate(url)

                            log.debug("waiting for networkidle")
                            page.waitForLoadState(LoadState.NETWORKIDLE)
                            log.debug("networkidle reached")

                            val dismissed = consentDismisser.dismiss(page)
                            if (dismissed.isNotEmpty()) {
                                log.debug("post-dismiss settle {}ms", POST_DISMISS_SETTLE_MS.toInt())
                                page.waitForTimeout(POST_DISMISS_SETTLE_MS)
                            }

                            log.debug("taking viewport screenshot")
                            val screenshot = page.screenshot(
                                Page.ScreenshotOptions()
                                    .setFullPage(false)
                                    .setType(ScreenshotType.PNG),
                            )
                            log.debug("screenshot captured bytes={}", screenshot.size)

                            log.debug("extracting visible body text")
                            val visibleText = page.innerText("body")
                            log.debug("visible text chars={}", visibleText.length)

                            PageCapture(screenshot = screenshot, visibleText = visibleText)
                        }
                    }
                }
        }

    private fun viewportContext(): Browser.NewContextOptions =
        Browser.NewContextOptions()
            .setViewportSize(ViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
            .setDeviceScaleFactor(1.0)

    private companion object {
        const val VIEWPORT_WIDTH = 1440
        const val VIEWPORT_HEIGHT = 1600
        const val POST_DISMISS_SETTLE_MS = 300.0
    }
}
