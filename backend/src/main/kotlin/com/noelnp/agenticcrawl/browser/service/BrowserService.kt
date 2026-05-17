package com.noelnp.agenticcrawl.browser.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ViewportSize
import com.noelnp.agenticcrawl.browser.config.BrowserProperties
import com.noelnp.agenticcrawl.browser.consent.ConsentDismisser
import com.noelnp.agenticcrawl.browser.session.LiveSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class BrowserService(
    private val consentDismisser: ConsentDismisser,
    private val properties: BrowserProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun openSession(url: String): LiveSession {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "playwright-session").apply { isDaemon = true }
        }

        return try {
            executor.submit<LiveSession> {
                log.debug("launching chromium headless={} channel={}", properties.headless, properties.channel)
                val playwright = Playwright.create()
                val browser = playwright.chromium().launch(launchOptions())
                val context = browser.newContext(contextOptions())
                context.addInitScript(STEALTH_INIT_SCRIPT)
                val page = context.newPage()

                log.info(
                    "navigating url={} viewport={}x{} waitUntil={} timeoutMs={}",
                    url, VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                    properties.waitUntil, properties.navigationTimeoutMs.toLong(),
                )
                try {
                    page.navigate(
                        url,
                        Page.NavigateOptions()
                            .setWaitUntil(properties.waitUntil)
                            .setTimeout(properties.navigationTimeoutMs),
                    )
                } catch (e: Exception) {
                    dumpFailureArtifacts(page, url)
                    throw e
                }

                log.debug("post-navigate settle {}ms", properties.postLoadSettleMs.toInt())
                page.waitForTimeout(properties.postLoadSettleMs)

                val dismissed = consentDismisser.dismiss(page)
                if (dismissed.isNotEmpty()) {
                    log.debug("post-dismiss settle {}ms", properties.postDismissSettleMs.toInt())
                    page.waitForTimeout(properties.postDismissSettleMs)
                }

                LiveSession(executor, playwright, browser, context, page, consentDismisser, properties)
            }.get()
        } catch (e: Exception) {
            executor.shutdownNow()
            throw e
        }
    }

    private fun launchOptions(): BrowserType.LaunchOptions {
        val options = BrowserType.LaunchOptions()
            .setHeadless(properties.headless)
            .setArgs(
                listOf(
                    "--disable-blink-features=AutomationControlled",
                    "--disable-features=IsolateOrigins,site-per-process",
                ),
            )
        properties.channel?.takeIf { it.isNotBlank() }?.let(options::setChannel)
        return options
    }

    private fun contextOptions(): Browser.NewContextOptions =
        Browser.NewContextOptions()
            .setViewportSize(ViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
            .setDeviceScaleFactor(1.0)
            .setUserAgent(properties.userAgent)
            .setLocale(properties.locale)
            .setTimezoneId(properties.timezoneId)
            .setExtraHTTPHeaders(mapOf("Accept-Language" to properties.acceptLanguage))

    private fun dumpFailureArtifacts(page: Page, url: String) {
        runCatching {
            val currentUrl = page.url()
            val title = runCatching { page.title() }.getOrDefault("<unavailable>")
            log.warn("navigation failed url={} landedOn={} title='{}'", url, currentUrl, title)
            val shot = java.nio.file.Files.createTempFile("playwright-failure-", ".png")
            page.screenshot(Page.ScreenshotOptions().setPath(shot).setFullPage(false))
            log.warn("captured failure screenshot at {}", shot)
        }.onFailure { log.warn("could not capture failure artifacts: {}", it.message) }
    }

    private companion object {
        const val VIEWPORT_WIDTH = 1440
        const val VIEWPORT_HEIGHT = 1600

        // Minimal stealth: hide the navigator.webdriver flag and a couple of other tells that
        // headless Chromium leaks. Not bulletproof, but defeats the cheap detection paths.
        const val STEALTH_INIT_SCRIPT = """
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            Object.defineProperty(navigator, 'languages', { get: () => ['sv-SE', 'sv', 'en-US', 'en'] });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            window.chrome = window.chrome || { runtime: {} };
            const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
            if (originalQuery) {
                window.navigator.permissions.query = (parameters) => (
                    parameters.name === 'notifications'
                        ? Promise.resolve({ state: Notification.permission })
                        : originalQuery(parameters)
                );
            }
        """
    }
}
