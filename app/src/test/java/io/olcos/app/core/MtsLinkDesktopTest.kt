package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MTS Link sheet's desktop identity: the user agent, the touch-hiding script and the two link decisions. */
class MtsLinkDesktopTest {
    @Test fun theUserAgentIsDesktopChromeOnWindowsAndNotTheSharedLinuxOne() {
        val ua = MtsLinkDesktop.USER_AGENT
        assertTrue(ua.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertTrue(ua.contains("Chrome/") && ua.endsWith("Safari/537.36"))
        assertFalse(ua.contains("Mobile") || ua.contains("Android") || ua.contains("Linux"))
        assertTrue(ua != RoomServices.BROWSER_UA)
    }

    @Test fun theShimOverridesTheTouchSignals() {
        val js = MtsLinkDesktop.SHIM
        for (needle in listOf("maxTouchPoints", "'platform', 'Win32'", "ontouchstart", "ontouchmove", "ontouchend",
            "Window.prototype", "Document.prototype", "HTMLElement.prototype", "matchMedia",
            "(pointer:coarse)", "(any-pointer:coarse)", "(hover:none)", "(pointer:fine)", "(any-pointer:fine)", "(hover:hover)")) {
            assertTrue("the shim lacks $needle", js.contains(needle))
        }
        assertTrue(js.contains("'(pointer:coarse)': false") && js.contains("'(pointer:fine)': true"))
        assertTrue(js.contains("'(hover:none)': false") && js.contains("'(hover:hover)': true"))
        assertFalse("a dollar sign would be interpolated", js.contains('$'))
    }

    @Test fun theShimLetsThePageBePinched() {
        val js = MtsLinkDesktop.SHIM
        // A page viewport that blocks scaling is rewritten, now and whenever the page sets one again.
        for (needle in listOf("meta[name=viewport]", "user-scalable", "maximum-scale", "minimum-scale", "MutationObserver")) {
            assertTrue("the shim lacks $needle", js.contains(needle))
        }
        assertTrue(js.contains("user-scalable=yes"))
    }

    @Test fun theViewportRewriteKeepsTheLayoutAndDropsOnlyTheScalingLocks() {
        assertEquals("width=device-width, initial-scale=1, user-scalable=yes, maximum-scale=10",
            MtsLinkDesktop.scalableViewport("width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"))
        assertEquals("width=1280, user-scalable=yes, maximum-scale=10",
            MtsLinkDesktop.scalableViewport("width=1280,user-scalable=0,minimum-scale=1"))
        assertEquals("user-scalable=yes, maximum-scale=10", MtsLinkDesktop.scalableViewport(""))
    }

    @Test fun theAddressLineShowsTheHostAndWhetherTheLinkIsSecure() {
        assertEquals(MtsLinkDesktop.Address("my.mts-link.ru", secure = true), MtsLinkDesktop.address("https://my.mts-link.ru/j/1/2?x=1#y"))
        assertEquals(MtsLinkDesktop.Address("events.mts-link.ru", secure = false), MtsLinkDesktop.address("http://Events.MTS-Link.ru"))
        assertEquals(MtsLinkDesktop.Address("id.example.org", secure = true), MtsLinkDesktop.address("https://user:pw@id.example.org:8443/login"))
        assertNull(MtsLinkDesktop.address("about:blank"))
        assertNull(MtsLinkDesktop.address(""))
    }

    @Test fun everyStepOfTheShimIsGuardedAndItRunsOncePerDocument() {
        val js = MtsLinkDesktop.SHIM
        assertTrue(js.contains("__olcosDesktop"))
        assertEquals("every defineProperty / delete / wrap sits in a try", js.split("try {").size - 1, js.split("catch (e)").size - 1)
        assertTrue(js.trim().startsWith("(function()") && js.trim().endsWith("})();"))
    }

    @Test fun nonWebSchemesAreBlockedByTheirName() {
        assertEquals("mtslink", MtsLinkDesktop.blockedLinkKind("mtslink://join/123"))
        assertEquals("intent", MtsLinkDesktop.blockedLinkKind("intent://my.mts-link.ru/j/1#Intent;scheme=mtslink;end"))
        assertEquals("itms-apps", MtsLinkDesktop.blockedLinkKind("itms-apps://apps.apple.com/app/id1"))
        assertEquals("tel", MtsLinkDesktop.blockedLinkKind("tel:+70000000000"))
        assertEquals("market", MtsLinkDesktop.blockedLinkKind("market://details?id=ru.mts.link"))
        assertEquals("mailto", MtsLinkDesktop.blockedLinkKind("  MAILTO:a@b.c "))
    }

    @Test fun appStoreLinksOverHttpsAreBlockedToo() {
        assertEquals("app-store", MtsLinkDesktop.blockedLinkKind("https://play.google.com/store/apps/details?id=ru.mts.link"))
        assertEquals("app-store", MtsLinkDesktop.blockedLinkKind("https://apps.apple.com/ru/app/mts-link/id1"))
        assertEquals("app-store", MtsLinkDesktop.blockedLinkKind("http://itunes.apple.com/app/id1"))
        assertEquals("app-store", MtsLinkDesktop.blockedLinkKind("https://APPS.RUSTORE.RU/app/ru.mts.link"))
    }

    @Test fun theSiteItselfAndPlainPagesAreNotBlocked() {
        assertNull(MtsLinkDesktop.blockedLinkKind("https://my.mts-link.ru/"))
        assertNull(MtsLinkDesktop.blockedLinkKind("http://my.mts-link.ru/j/1/2?x=1"))
        assertNull(MtsLinkDesktop.blockedLinkKind("https://play.google.com/about"))
        assertNull(MtsLinkDesktop.blockedLinkKind("https://example.com/store/apple.com"))
        assertNull(MtsLinkDesktop.blockedLinkKind("https://example.com/?next=https://apps.apple.com/"))
        assertNull(MtsLinkDesktop.blockedLinkKind("about:blank"))
        assertNull(MtsLinkDesktop.blockedLinkKind("blob:https://my.mts-link.ru/4d5e"))
        assertNull(MtsLinkDesktop.blockedLinkKind("javascript:void(0)"))
        assertNull(MtsLinkDesktop.blockedLinkKind("no-scheme/path"))
    }

    @Test fun theLogKeepsSchemeHostAndPathOnly() {
        assertEquals("https://my.mts-link.ru/j/1/2", MtsLinkDesktop.logTarget("https://my.mts-link.ru/j/1/2?token=abc#frag"))
        assertEquals("https://my.mts-link.ru/", MtsLinkDesktop.logTarget(" https://my.mts-link.ru/#x"))
        assertEquals("mtslink:", MtsLinkDesktop.logTarget("mtslink://join?code=secret"))
        assertEquals("intent:", MtsLinkDesktop.logTarget("intent://x#Intent;S.browser_fallback_url=https%3A%2F%2Fa;end"))
        assertFalse(MtsLinkDesktop.logTarget("https://a.b/c?secret=1").contains("secret"))
    }
}
