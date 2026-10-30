package com.zenith.manager.accounts;

import com.zenith.manager.util.Json;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CredentialLoginTest {
    @Test void parsesCookieHeaderWithoutLosingEqualsInValues() {
        var cookies = CookieMsaAuthService.parseCookies("Cookie: MSPAuth=abc==; MSPProf=xyz");
        assertEquals(2, cookies.size());
        assertEquals("abc==", cookies.getFirst().getValue());
        assertEquals("/", cookies.getFirst().getPath());
        assertTrue(cookies.getFirst().getSecure());
    }

    @Test void netscapeExportOnlyUsesMicrosoftLoginCookies() {
        var cookies = CookieMsaAuthService.parseCookies("""
            # Netscape HTTP Cookie File
            .example.com\tTRUE\t/\tTRUE\t0\tsecret\tignored
            #HttpOnly_.login.live.com\tTRUE\t/\tTRUE\t0\tMSPAuth\tvalue
            .live.com\tTRUE\t/\tTRUE\t0\tMSPProf\tprofile
            """);
        assertEquals(2, cookies.size());
        assertEquals("MSPAuth", cookies.getFirst().getName());
        assertThrows(IllegalArgumentException.class, () -> CookieMsaAuthService.parseCookies("# empty"));
        assertThrows(IllegalArgumentException.class, () -> CookieMsaAuthService.parseCookies("malformed"));
    }

    @Test void redirectCannotSendCookiesToAnotherHostOrPlainHttp() {
        assertTrue(CookieMsaAuthService.isMicrosoftLogin(URI.create("https://login.live.com/oauth20_authorize.srf")));
        for (var uri : new String[]{"http://login.live.com/", "https://login.live.com.evil.test/", "https://evil.test/", "https://login.live.com:8443/", "https://user@login.live.com/"}) {
            assertFalse(CookieMsaAuthService.isMicrosoftLogin(URI.create(uri)));
        }
    }

    @Test void netscapeExportAcceptsEmptyValuesWithOrWithoutFinalTab() {
        var cookies = CookieMsaAuthService.parseCookies("""
            Account summary
            .live.com\tTRUE\t/\tTRUE\t0\tWLSSC\tsession
            .msn.com\tTRUE\t/service/msn\tTRUE\t0\tMUID
            .msn.com\tTRUE\t/service/news/feed\tTRUE\t0\tMUIDB\t
            .login.live.com\tTRUE\t/\tTRUE\t0\tEmptyCookie
            .login.live.com\tTRUE\t/\tTRUE\t0\tAnotherEmptyCookie\t
            """);
        assertEquals(3, cookies.size());
        assertEquals("session", cookies.getFirst().getValue());
        assertEquals("", cookies.get(1).getValue());
        assertEquals("", cookies.get(2).getValue());
        assertThrows(IllegalArgumentException.class, () -> CookieMsaAuthService.parseCookies(
            ".login.live.com\tTRUE\t/\tTRUE\t0"));
    }

    @Test void netscapeExportMayHaveSummaryAndSeparateAccessToken() {
        var cookies = CookieMsaAuthService.parseCookies("""
            \uFEFF[Account summary][XX][2026-10-30 08:14:23] AccessToken: header.payload.signature

            .example.com\tTRUE\t/\tTRUE\t0\tunrelated\tdo-not-import
            .live.com\tTRUE\t/\tTRUE\t0\tWLSSC\tsession==
            login.live.com\tFALSE\t/\tTRUE\t0\t__Host-MSAAUTHP\tauth
            """);
        assertEquals(2, cookies.size());
        assertEquals("WLSSC", cookies.getFirst().getName());
        assertEquals("session==", cookies.getFirst().getValue());
        assertEquals("__Host-MSAAUTHP", cookies.getLast().getName());
        assertThrows(IllegalArgumentException.class, () -> CookieMsaAuthService.parseCookies("""
            Account summary
            .example.com\tTRUE\t/\tTRUE\t0\tunrelated\tignored
            """));
        assertThrows(IllegalArgumentException.class, () -> CookieMsaAuthService.parseCookies("""
            .live.com\tTRUE\t/\tTRUE\t0\tWLSSC\tsession
            broken row
            """));
    }

    @Test void tokenExpiryRejectsMalformedAndExpiredTokens() {
        long future = System.currentTimeMillis() / 1000 + 3600;
        assertEquals(future * 1000, AccountService.tokenExpiry(jwt(future)));
        assertThrows(IllegalArgumentException.class, () -> AccountService.tokenExpiry(jwt(1)));
        assertThrows(IllegalArgumentException.class, () -> AccountService.tokenExpiry("not-a-token"));
    }

    @Test void microsoftCallbackReportsRequiredConsentWithoutLeakingDescription() throws Exception {
        var callback = URI.create(CookieMsaAuthService.CALLBACK + "?state=expected&error=consent_required&error_description=private-data");
        var error = assertThrows(CookieMsaAuthService.SignInRequiredException.class,
            () -> CookieMsaAuthService.authorizationCode(callback, "expected"));
        assertTrue(error.getMessage().contains("requires permission"));
        assertFalse(error.getMessage().contains("private-data"));
        assertThrows(java.io.IOException.class, () -> CookieMsaAuthService.authorizationCode(callback, "wrong-state"));
        assertEquals("auth-code", CookieMsaAuthService.authorizationCode(
            URI.create(CookieMsaAuthService.CALLBACK + "?state=expected&code=auth-code"), "expected"));
        assertThrows(java.io.IOException.class, () -> CookieMsaAuthService.authorizationCode(
            URI.create(CookieMsaAuthService.CALLBACK + "?state=expected"), "expected"));
    }

    @Test void tokenOnlySessionSurvivesAuthCacheRoundTrip() {
        var session = JavaAuthManager.create(MinecraftAuth.createHttpClient()).login(new MsaToken(0, "", ""));
        session.getMinecraftToken().set(new MinecraftToken(System.currentTimeMillis() + 3600000, "Bearer", "test-token"));
        var restored = JavaAuthManager.fromJson(MinecraftAuth.createHttpClient(), JavaAuthManager.toJson(session));
        assertEquals("test-token", restored.getMinecraftToken().getUpToDateUnchecked().getToken());
        assertEquals("", restored.getMsaToken().getCached().getRefreshToken());
        assertFalse(Json.GSON.toJson(new AccountService.PendingLogin(null, "owner").toJson()).contains("test-token"));
    }

    @Test void microsoftContinuationAndConsentOnlySubmitRequestedLauncherScopes() {
        var login = URI.create("https://login.live.com/oauth20_authorize.srf");
        var consent = URI.create("https://account.live.com/Consent/Update");
        var continuation = """
            <title>Continue</title><form method="post" action="https://account.live.com/Consent/Update">
            <input type="hidden" name="rd" value="return&amp;state=expected">
            <input type="hidden" name="pprid" value="private-id"><input type="hidden" name="ipt" value="proof+=">
            <input type="hidden" name="uaid" value="request-id">
            <input type="hidden" name="client_id" value="00000000402b5328">
            <input type="hidden" name="scope" value="XboxLive.signin XboxLive.offline_access"></form>
            """;
        var post = CookieMsaAuthService.consentPost(login, continuation);
        assertNotNull(post);
        assertEquals(consent, post.uri());
        assertTrue(post.body().contains("ipt=proof%2B%3D"));
        assertTrue(post.body().contains("rd=return%26state%3Dexpected"));
        var permission = """
            <form method="post" action="/Consent/Update">
            <input type="hidden" name="ucaction" value="">
            <input type="hidden" name="client_id" value="00000000402b5328">
            <input type="hidden" name="scope" value="00000000402B5328:XboxLive.signin 00000000402B5328:XboxLive.offline_access">
            <input type="hidden" name="cscope" value=""><input type="hidden" name="canary" value="csrf-token">
            <button type="submit">Deny</button><button type="submit">Accept</button></form>
            """;
        var approval = CookieMsaAuthService.consentPost(consent, permission);
        assertNotNull(approval);
        assertTrue(approval.body().contains("ucaction=Yes"));
        assertNull(CookieMsaAuthService.consentPost(consent, permission.replace("XboxLive.offline_access", "Mail.Read")));
        assertNull(CookieMsaAuthService.consentPost(consent, permission.replace("name=\"cscope\" value=\"\"", "name=\"cscope\" value=\"Mail.Read\"")));
        assertNull(CookieMsaAuthService.consentPost(consent, permission.replace("00000000402b5328", "another-app")));
        assertNull(CookieMsaAuthService.consentPost(consent, permission.replace("csrf-token", "")));
        assertNull(CookieMsaAuthService.consentPost(login, continuation.replace("account.live.com", "evil.test")));
        assertNull(CookieMsaAuthService.consentPost(consent, permission.replace("</form>", "<input type=password name=password></form>")));
        assertNull(CookieMsaAuthService.consentPost(URI.create("https://evil.test/"), continuation));
    }

    @Test void microsoftCookieDomainsSurviveExportAndHostOnlyCookiesStayOnLogin() throws Exception {
        var jar = new java.net.CookieManager();
        var origin = URI.create("https://login.live.com/");
        for (var cookie : CookieMsaAuthService.parseCookies("""
            .live.com\tTRUE\t/\tTRUE\t0\tWLSSC\tsession
            login.live.com\tFALSE\t/\tTRUE\t0\t__Host-MSAAUTHP\thost-session
            """)) jar.getCookieStore().add(origin, cookie);
        var consentCookies = jar.get(URI.create("https://account.live.com/Consent/Update"), java.util.Map.of()).get("Cookie").toString();
        assertTrue(consentCookies.contains("WLSSC=session"));
        assertFalse(consentCookies.contains("__Host-MSAAUTHP"));
    }

    @Test void scriptRenderedConsentKeepsReturnQueryAndRejectsExtraPermissions() {
        var page = URI.create("https://account.live.com/Consent/Update?ru=return-to-oauth&uaid=request-id");
        var html = """
            <script>var ServerData = {
                "sClientId":"00000000402b5328",
                "sRawInputScopes":"",
                "sRawInputGrantedScopes":"00000000402B5328:XboxLive.signin 00000000402B5328:XboxLive.offline_access",
                "sCanary":"csrf-token"
            }; unrelatedScript();</script>
            """;
        var post = CookieMsaAuthService.consentPost(page, html);
        assertNotNull(post);
        assertEquals(page, post.uri()); // Microsoft needs this query to resume the original OAuth request.
        assertTrue(post.body().contains("ucaction=Yes"));
        assertNull(CookieMsaAuthService.consentPost(page, html.replace("XboxLive.offline_access", "Mail.Read")));
        assertNull(CookieMsaAuthService.consentPost(page, html.replace("00000000402b5328", "another-client")));
        assertNull(CookieMsaAuthService.consentPost(page, html.replace("csrf-token", "")));
        assertNull(CookieMsaAuthService.consentPost(URI.create("https://login.live.com/"), html));
        assertNull(CookieMsaAuthService.consentPost(URI.create("https://evil.test/Consent/Update"), html));
    }

    private static String jwt(long expiry) {
        return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"exp\":" + expiry + "}").getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
