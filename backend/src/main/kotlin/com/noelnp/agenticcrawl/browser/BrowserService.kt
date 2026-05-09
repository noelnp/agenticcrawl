package com.noelnp.agenticcrawl.browser

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ScreenshotType
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BrowserService(private val consentDismisser: ConsentDismisser) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun captureScreenshot(url: String): ByteArray =
        Playwright.create().use { playwright ->
            log.debug("launching headless chromium")
            playwright.chromium()
                .launch(BrowserType.LaunchOptions().setHeadless(true))
                .use { browser ->
                    browser.newContext().use { context ->
                        context.newPage().use { page ->
                            log.info("navigating url={}", url)
                            page.navigate(url)

                            log.debug("waiting for networkidle")
                            page.waitForLoadState(LoadState.NETWORKIDLE)
                            log.debug("networkidle reached")

                            val dismissed = consentDismisser.dismiss(page)
                            if (dismissed.isNotEmpty()) {
                                log.debug("post-dismiss settle {}ms", POST_DISMISS_SETTLE_MS.toInt())
                                page.waitForTimeout(POST_DISMISS_SETTLE_MS)
                            }

                            log.debug("taking full-page screenshot")
                            page.screenshot(
                                Page.ScreenshotOptions()
                                    .setFullPage(true)
                                    .setType(ScreenshotType.PNG),
                            ).also {
                                log.debug("screenshot captured bytes={}", it.size)
                            }
                        }
                    }
                }
        }

    private companion object {
        const val POST_DISMISS_SETTLE_MS = 300.0
    }
}
