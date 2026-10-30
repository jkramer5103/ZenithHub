package com.zenith.manager.accounts;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.request.MsaAuthCodeTokenRequest;
import net.raphimc.minecraftauth.msa.service.MsaAuthService;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import org.jsoup.Jsoup;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Exchanges an existing Microsoft browser session; never saves the supplied cookies. */
final class CookieMsaAuthService extends MsaAuthService {
    static final String CALLBACK = "https://login.live.com/oauth20_desktop.srf";
    private static final URI ORIGIN = URI.create("https://login.live.com/");
    private final String cookies;

    CookieMsaAuthService(HttpClient client, MsaApplicationConfig config, String cookies) {
        super(client, config);
        this.cookies = cookies;
    }

    @Override
    public MsaToken acquireToken() throws IOException, InterruptedException {
        var jar = new CookieManager();
        for (var cookie : parseCookies(cookies)) jar.getCookieStore().add(ORIGIN, cookie);
        var state = UUID.randomUUID().toString();
        var parameters = applicationConfig.getAuthCodeParameters();
        parameters.put("state", state);
        var query = parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
        var uri = URI.create("https://login.live.com/oauth20_authorize.srf?" + query);
        FormPost post = null;
        var builder = java.net.http.HttpClient.newBuilder().cookieHandler(jar)
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20));
        // the same proxy relay as the rest of the sign-in
        var proxy = httpClient.getProxyHandler();
        if (proxy.isProxySet()) builder.proxy(java.net.ProxySelector.of((java.net.InetSocketAddress) proxy.getAddress()));
        try (var client = builder.build()) {
            for (int i = 0; i < 16; i++) {
                if (!isMicrosoftLogin(uri) && !isConsentEndpoint(uri)) throw new IOException("Unexpected Microsoft login destination");
                if (isMicrosoftLogin(uri) && uri.getPath().equals("/oauth20_desktop.srf")) {
                    return httpClient.executeAndHandle(new MsaAuthCodeTokenRequest(applicationConfig, authorizationCode(uri, state)));
                }
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
                if (post == null) request.GET();
                else request.header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://" + uri.getHost())
                    .POST(HttpRequest.BodyPublishers.ofString(post.body()));
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                post = null;
                if (response.statusCode() == 200) {
                    post = consentPost(uri, response.body());
                    if (post == null) break;
                    uri = post.uri();
                    continue;
                }
                if (response.statusCode() < 300 || response.statusCode() >= 400) break;
                var location = response.headers().firstValue("Location");
                if (location.isEmpty()) break;
                uri = uri.resolve(location.get());
            }
        }
        throw new SignInRequiredException("Microsoft requires an interactive sign-in. Use the Microsoft tab; the cookie file alone cannot complete this login.");
    }

    record FormPost(URI uri, String body) {}

    /** Only the official launcher's two requested Xbox scopes may be approved. Never submit login/MFA forms. */
    static FormPost consentPost(URI page, String html) {
        if (!isMicrosoftLogin(page) && !isConsentEndpoint(page)) return null;
        var document = Jsoup.parse(html, page.toString());
        for (var form : document.select("form[method=post][action]")) {
            var destination = page.resolve(form.attr("action"));
            if (!isConsentEndpoint(destination)) continue;
            if (!form.select("input:not([type=hidden])").isEmpty()) continue;
            var fields = new LinkedHashMap<String, String>();
            for (var field : form.select("input[type=hidden][name]")) fields.put(field.attr("name"), field.val());
            if (!MsaConstants.JAVA_TITLE_ID.equalsIgnoreCase(fields.get("client_id"))) continue;
            var scope = fields.getOrDefault("scope", "");
            var cscope = fields.getOrDefault("cscope", "");
            if (!requestedXboxScopes(scope, cscope)) continue;
            if (document.title().equals("Continue") && isMicrosoftLogin(page)
                && (fields.keySet().equals(Set.of("rd", "pprid", "ipt", "uaid", "client_id", "scope"))
                    || fields.keySet().equals(Set.of("rd", "pprid", "ipt", "uaid", "client_id", "cscope")))) {
                return formPost(destination, fields);
            }
            if (isConsentEndpoint(page) && fields.keySet().equals(Set.of("ucaction", "client_id", "scope", "cscope", "canary"))
                && !fields.get("canary").isBlank()) {
                fields.put("ucaction", "Yes");
                return formPost(destination, fields);
            }
        }
        // Current Microsoft consent pages render their form from this JSON configuration.
        // Read only the five form fields; do not execute scripts or follow arbitrary page actions.
        if (isConsentEndpoint(page)) for (var script : document.select("script")) {
            var assignment = java.util.regex.Pattern.compile("\\bServerData\\s*=\\s*(?=\\{)").matcher(script.data());
            if (!assignment.find()) continue;
            try (var reader = new JsonReader(new StringReader(script.data().substring(assignment.end())))) {
                var data = JsonParser.parseReader(reader).getAsJsonObject();
                var fields = new LinkedHashMap<String, String>();
                for (var mapping : Map.of("client_id", "sClientId", "scope", "sRawInputScopes",
                    "cscope", "sRawInputGrantedScopes", "canary", "sCanary").entrySet()) {
                    fields.put(mapping.getKey(), data.get(mapping.getValue()).getAsString());
                }
                if (!MsaConstants.JAVA_TITLE_ID.equalsIgnoreCase(fields.get("client_id")) || fields.get("canary").isBlank()
                    || !requestedXboxScopes(fields.get("scope"), fields.get("cscope"))) continue;
                fields.put("ucaction", "Yes");
                return formPost(page, fields);
            } catch (RuntimeException ignored) {
                // Unrecognised page data must fall back to interactive sign-in, never a broader approval.
            } catch (IOException ignored) {
                // StringReader has no external resources.
            }
        }
        return null;
    }

    private static boolean requestedXboxScopes(String scope, String cscope) {
        var requested = new java.util.HashSet<String>();
        for (var field : java.util.List.of(scope, cscope)) {
            if (field.isBlank()) continue;
            for (var value : field.strip().split("\\s+")) {
                var parts = value.split(":", -1);
                if (parts.length == 2 && MsaConstants.JAVA_TITLE_ID.equalsIgnoreCase(parts[0])) value = parts[1];
                else if (parts.length != 1) return false;
                requested.add(value);
            }
        }
        return requested.equals(Set.of("XboxLive.signin", "XboxLive.offline_access"));
    }

    private static FormPost formPost(URI uri, Map<String, String> fields) {
        return new FormPost(uri, fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&")));
    }

    static boolean isConsentEndpoint(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "account.live.com".equalsIgnoreCase(uri.getHost())
            && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
            && "/Consent/Update".equals(uri.getPath());
    }

    /** Only our fixed messages may reach the UI; OAuth descriptions can contain private data. */
    static final class SignInRequiredException extends IOException {
        private SignInRequiredException(String message) { super(message); }
    }

    static String authorizationCode(URI callback, String expectedState) throws IOException {
        var result = queryParameters(callback);
        if (!expectedState.equals(result.get("state"))) throw new IOException("Invalid Microsoft login state");
        if (result.containsKey("error")) {
            throw new SignInRequiredException(switch (result.get("error")) {
                case "consent_required" -> "Microsoft requires permission to let ZenithHub sign in and renew this account. Use the Microsoft tab to approve it; re-uploading the cookies will not grant permission.";
                case "login_required" -> "Microsoft did not accept these cookies for sign-in. Use the Microsoft tab to sign in again. This does not mean the separate Minecraft access token is invalid.";
                default -> "Microsoft requires an interactive sign-in. Use the Microsoft tab; the cookie file alone cannot complete this login.";
            });
        }
        var code = result.get("code");
        if (code == null || code.isBlank()) throw new IOException("Missing Microsoft authorization code");
        return code;
    }

    static boolean isMicrosoftLogin(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "login.live.com".equalsIgnoreCase(uri.getHost())
            && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    /** Accept a Netscape export (optionally preceded by a summary) or a Cookie header. */
    static java.util.List<HttpCookie> parseCookies(String input) {
        var result = new java.util.ArrayList<HttpCookie>();
        var lines = input.replaceFirst("^\\uFEFF", "").strip().split("\\R");
        // Some exports prepend account metadata, including a separate access token.
        // Once cookie rows are present, never interpret that metadata as credentials.
        boolean netscape = java.util.Arrays.stream(lines).anyMatch(line -> line.contains("\t"));
        boolean startedRows = false;
        for (var raw : lines) {
            var line = raw.strip();
            if (line.startsWith("#HttpOnly_")) line = line.substring(10);
            else if (line.startsWith("#") || line.isBlank()) continue;
            if (netscape) {
                if (!line.contains("\t") && !startedRows) continue;
                var fields = line.split("\t", -1);
                // Exporters may omit the final tab when a cookie value is empty.
                if (fields.length != 6 && fields.length != 7) throw new IllegalArgumentException("Invalid Netscape cookie file");
                startedRows = true;
                var domain = fields[0].replaceFirst("^\\.", "");
                if (!domain.equalsIgnoreCase("login.live.com") && !domain.equalsIgnoreCase("live.com")) continue;
                var cookie = cookie(fields[5], fields.length == 7 ? fields[6] : "");
                // Preserve domain scope: host-only login cookies must never be sent to account.live.com.
                if (fields[1].equalsIgnoreCase("TRUE") && !fields[5].startsWith("__Host-")) cookie.setDomain("." + domain);
                cookie.setPath(fields[2].isEmpty() ? "/" : fields[2]);
                result.add(cookie);
            } else {
                if (line.regionMatches(true, 0, "Cookie:", 0, 7)) line = line.substring(7).strip();
                for (var pair : line.split(";")) {
                    var separator = pair.indexOf('=');
                    if (separator <= 0) throw new IllegalArgumentException("Paste a Netscape cookie file or Cookie header");
                    result.add(cookie(pair.substring(0, separator).strip(), pair.substring(separator + 1).strip()));
                }
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No Microsoft login cookies found");
        return result;
    }

    private static HttpCookie cookie(String name, String value) {
        if (value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid cookie value");
        var cookie = new HttpCookie(name, value);
        cookie.setVersion(0);
        cookie.setSecure(true);
        cookie.setPath("/");
        return cookie;
    }

    private static Map<String, String> queryParameters(URI uri) {
        var result = new java.util.HashMap<String, String>();
        if (uri.getRawQuery() != null) for (var pair : uri.getRawQuery().split("&")) {
            var fields = pair.split("=", 2);
            if (fields.length == 2) result.put(URLDecoder.decode(fields[0], StandardCharsets.UTF_8), URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
