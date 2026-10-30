package com.zenith.manager.accounts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.instances.OutboundProxies;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.SecureFiles;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.proxy.ProxyHandler;
import net.lenni0451.commons.httpclient.proxy.ProxyType;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.java.exception.MinecraftProfileNotFoundException;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.util.MinecraftAuth4To5Migrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Owns Minecraft account logins. Instances never prompt for logins themselves:
 * the manager runs device-code logins, refreshes tokens in the background,
 * and copies the account's auth cache into each instance before it starts.
 */
public final class AccountService {
    private static final Logger LOG = LoggerFactory.getLogger("Accounts");
    private static final String AUTH_CACHE = "mc_auth_cache.json";
    private static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    /** Refresh when the Minecraft token expires within this window (tokens last ~24h). */
    private static final long REFRESH_AHEAD_MS = Duration.ofHours(6).toMillis();

    public enum LoginState { WAITING_FOR_CODE, WAITING_FOR_USER, DONE, FAILED, CANCELLED }

    public static final class PendingLogin {
        public final String id = UUID.randomUUID().toString();
        /** Existing account being logged in again, or null for a new account. */
        public final String reloginAccountId;
        /** The user adding the account; only they (and admins) see this login. */
        public final String ownerId;
        public volatile LoginState state = LoginState.WAITING_FOR_CODE;
        public volatile String userCode;
        public volatile String verificationUri;
        public volatile String directVerificationUri;
        public volatile long expiresAt;
        public volatile String accountId;
        public volatile String error;
        volatile Thread thread;

        PendingLogin(String reloginAccountId, String ownerId) {
            this.reloginAccountId = reloginAccountId;
            this.ownerId = ownerId;
        }

        public JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("state", state.name());
            json.addProperty("userCode", userCode);
            json.addProperty("verificationUri", verificationUri);
            json.addProperty("directVerificationUri", directVerificationUri);
            json.addProperty("expiresAt", expiresAt);
            json.addProperty("accountId", accountId);
            json.addProperty("error", error);
            json.addProperty("ownerId", ownerId);
            return json;
        }
    }

    private final ManagerConfig config;
    private final StateStore store;
    private final EventHub events;
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("account-refresh").daemon().factory());

    /** Set once the supervisor exists; until then requests go direct (nothing runs that early). */
    private volatile OutboundProxies proxies;

    public void setProxies(OutboundProxies proxies) {
        this.proxies = proxies;
    }

    public AccountService(ManagerConfig config, StateStore store, EventHub events) {
        this.config = config;
        this.store = store;
        this.events = events;
    }

    public void startBackgroundRefresh() {
        scheduler.scheduleWithFixedDelay(this::refreshAll, 1, 30, TimeUnit.MINUTES);
    }

    // --- adding accounts ---

    public PendingLogin startDeviceCodeLogin(String reloginAccountId, String ownerId) {
        var login = new PendingLogin(reloginAccountId, ownerId);
        pending.put(login.id, login);
        login.thread = Thread.ofVirtual().name("device-code-login").start(() -> runDeviceCodeLogin(login));
        // drop finished logins after a while
        scheduler.schedule(() -> pending.remove(login.id), 30, TimeUnit.MINUTES);
        return login;
    }

    /** Credentials stay on the worker, never in PendingLogin or WebSocket events. */
    public PendingLogin startCredentialLogin(String method, String credential, String clientId, String reloginAccountId, String ownerId) {
        if (!java.util.Set.of("cookie", "token", "refresh-token").contains(method)) {
            throw new IllegalArgumentException("Unknown login method");
        }
        if (credential == null || credential.isBlank() || credential.length() > 262144) {
            throw new IllegalArgumentException("Enter a cookie or token (maximum 256 KB)");
        }
        var login = new PendingLogin(reloginAccountId, ownerId);
        pending.put(login.id, login);
        login.thread = Thread.ofVirtual().name("credential-login").start(() -> {
            try {
                var builder = JavaAuthManager.create(httpClient(reloginAccountId));
                JavaAuthManager session;
                switch (method) {
                    case "cookie" -> session = builder.msaApplicationConfig(new MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID,
                        MsaConstants.SCOPE_OFFLINE_ACCESS).withRedirectUri(CookieMsaAuthService.CALLBACK))
                        .login(CookieMsaAuthService::new, credential);
                    case "refresh-token" -> {
                        if (clientId != null && !clientId.isBlank()) {
                            if (!clientId.matches("[a-fA-F0-9-]{16,36}")) throw new IllegalArgumentException("Invalid application client ID");
                            builder.msaApplicationConfig(new MsaApplicationConfig(clientId, MsaConstants.SCOPE_OFFLINE_ACCESS));
                        }
                        session = builder.login(credential.strip());
                    }
                    case "token" -> {
                        var token = credential.strip().replaceFirst("(?i)^Bearer\\s+", "");
                        session = builder.login(new MsaToken(0, "", ""));
                        session.getMinecraftToken().set(new MinecraftToken(tokenExpiry(token), "Bearer", token));
                    }
                    default -> throw new IllegalArgumentException("Unknown login method");
                }
                // Validate identity with Minecraft before saving externally supplied credentials.
                session.getMinecraftProfile().refresh();
                session.getMinecraftPlayerCertificates().refresh();
                synchronized (login) {
                    if (login.state == LoginState.CANCELLED) return;
                    login.accountId = saveMsaSession(session, reloginAccountId, ownerId);
                    login.state = LoginState.DONE;
                }
            } catch (Exception e) {
                if (login.state != LoginState.CANCELLED) {
                    login.state = LoginState.FAILED;
                    // Upstream exceptions can contain request bodies or credentials.
                    login.error = e instanceof CookieMsaAuthService.SignInRequiredException ? e.getMessage()
                        : e instanceof IllegalArgumentException ? "Invalid cookie, token, or application client ID. Check the selected method and try again."
                        : "Sign-in failed. Check that the credentials are current and belong to an account with Minecraft: Java Edition, or use Microsoft login.";
                    LOG.warn("{} sign-in failed ({})", method, e.getClass().getSimpleName());
                }
            }
            publishLogin(login);
        });
        scheduler.schedule(() -> pending.remove(login.id), 30, TimeUnit.MINUTES);
        return login;
    }

    static long tokenExpiry(String token) {
        try {
            var parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException();
            var claims = JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            var expires = Math.multiplyExact(claims.get("exp").getAsLong(), 1000L);
            if (expires <= System.currentTimeMillis()) throw new IllegalArgumentException();
            return expires; // Only scheduling metadata; Minecraft verifies the token and account identity.
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter a current Minecraft access token");
        }
    }

    public Optional<PendingLogin> pendingLogin(String id) {
        return Optional.ofNullable(pending.get(id));
    }

    public void cancelLogin(String id) {
        var login = pending.get(id);
        if (login == null) return;
        synchronized (login) {
            if (login.state == LoginState.DONE || login.state == LoginState.FAILED) return;
            login.state = LoginState.CANCELLED;
        }
        var t = login.thread;
        if (t != null) t.interrupt();
        publishLogin(login);
    }

    private void runDeviceCodeLogin(PendingLogin login) {
        try {
            var session = JavaAuthManager.create(httpClient(login.reloginAccountId))
                .login(DeviceCodeMsaAuthService::new, (Consumer<MsaDeviceCode>) code -> {
                    login.userCode = code.getUserCode();
                    login.verificationUri = code.getVerificationUri();
                    login.directVerificationUri = code.getDirectVerificationUri();
                    login.expiresAt = code.getExpireTimeMs();
                    login.state = LoginState.WAITING_FOR_USER;
                    publishLogin(login);
                });
            if (login.state == LoginState.CANCELLED) return;
            login.accountId = saveMsaSession(session, login.reloginAccountId, login.ownerId);
            login.state = LoginState.DONE;
        } catch (InterruptedException e) {
            login.state = LoginState.CANCELLED;
        } catch (Exception e) {
            if (login.state != LoginState.CANCELLED) {
                LOG.warn("Device code login failed", e);
                login.state = LoginState.FAILED;
                login.error = friendlyError(e);
            }
        }
        publishLogin(login);
    }

    /** Imports an existing mc_auth_cache.json (MinecraftAuth v4 or v5 format). */
    public String importAuthCache(String json, String ownerId) throws Exception {
        var parsed = JsonParser.parseString(json).getAsJsonObject();
        if (parsed.get("_saveVersion") == null) parsed = MinecraftAuth4To5Migrator.migrateJavaSave(parsed);
        return saveMsaSession(JavaAuthManager.fromJson(httpClient(null), parsed), null, ownerId);
    }

    public String addOffline(String username, String ownerId) {
        if (!MC_NAME.matcher(username).matches()) throw new IllegalArgumentException("Invalid Minecraft username");
        // same UUID scheme vanilla uses for offline players
        var uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        return store.updateAndGet(s -> {
            if (s.accounts.stream().anyMatch(a -> a.type == State.AccountType.OFFLINE && a.username.equalsIgnoreCase(username))) {
                throw new IllegalArgumentException("An offline account with that name already exists");
            }
            var account = new State.Account();
            account.id = shortId();
            account.type = State.AccountType.OFFLINE;
            account.username = username;
            account.uuid = uuid;
            account.ownerId = ownerId;
            account.addedAt = System.currentTimeMillis();
            s.accounts.add(account);
            return account.id;
        });
    }

    public void delete(String accountId) throws IOException {
        store.update(s -> {
            if (s.instances.stream().anyMatch(i -> i.accountId.equals(accountId))) {
                throw new IllegalStateException("Delete this account's instances first");
            }
            s.accounts.removeIf(a -> a.id.equals(accountId));
        });
        SecureFiles.deleteRecursively(config.accountDir(accountId));
    }

    private String saveMsaSession(JavaAuthManager session, String reloginAccountId, String ownerId) throws IOException {
        // a fresh login only holds the Microsoft side: fetch the Minecraft parts now
        session.getMinecraftToken().getUpToDate();
        var profile = session.getMinecraftProfile().getUpToDate();
        session.getMinecraftPlayerCertificates().getUpToDate();
        var uuid = profile.getId().toString();
        var expiresAt = tokenOnlyExpiry(session);
        var accountId = store.updateAndGet(s -> {
            State.Account account;
            if (reloginAccountId != null) {
                account = s.accounts.stream().filter(a -> a.id.equals(reloginAccountId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Account was deleted"));
                if (account.uuid != null && !account.uuid.equals(uuid)) {
                    throw new IllegalStateException("Logged into " + profile.getName() + ", but this account is " + account.username);
                }
            } else {
                account = s.accounts.stream().filter(a -> a.type == State.AccountType.MSA && uuid.equals(a.uuid)).findFirst().orElse(null);
                if (account != null && !java.util.Objects.equals(account.ownerId, ownerId)) {
                    throw new IllegalStateException(profile.getName() + " was already added by someone else");
                }
                if (account == null) {
                    account = new State.Account();
                    account.id = shortId();
                    account.type = State.AccountType.MSA;
                    account.ownerId = ownerId;
                    account.addedAt = System.currentTimeMillis();
                    s.accounts.add(account);
                }
            }
            account.username = profile.getName();
            account.uuid = uuid;
            account.authError = null;
            account.tokenExpiresAt = expiresAt;
            return account.id;
        });
        writeAuthCache(accountId, session);
        LOG.info("Saved login for {} ({})", profile.getName(), accountId);
        return accountId;
    }

    // --- refresh + sync ---

    private void refreshAll() {
        var msaAccounts = store.read(s -> s.accounts.stream().filter(a -> a.type == State.AccountType.MSA).map(a -> a.id).toList());
        for (var id : msaAccounts) {
            try {
                refresh(id, false);
            } catch (Exception e) {
                LOG.warn("Background refresh failed for account {}", id, e);
            }
        }
    }

    public void refresh(String accountId, boolean force) throws IOException {
        var file = config.accountDir(accountId).resolve(AUTH_CACHE);
        if (!Files.exists(file)) return;
        try {
            var session = JavaAuthManager.fromJson(httpClient(accountId), Json.readObject(file));
            var token = session.getMinecraftToken().getCached();
            var msa = session.getMsaToken().getCached();
            if (msa != null && msa.getRefreshToken().isEmpty()) {
                // accounts added before this was recorded learn it on the first background check
                long expiresAt = token != null ? token.getExpireTimeMs() : 1;
                store.update(s -> s.accounts.stream().filter(a -> a.id.equals(accountId)).forEach(a -> a.tokenExpiresAt = expiresAt));
                if (token == null || token.isExpired()) throw new IOException("Access token expired. Log in again from the web UI.");
                if (force) throw new IOException("Access tokens cannot be refreshed. Log in again to replace it.");
                return;
            }
            if (!force && token != null && token.getExpireTimeMs() - System.currentTimeMillis() > REFRESH_AHEAD_MS) return;
            session.getMinecraftToken().refresh();
            session.getMinecraftProfile().refresh();
            session.getMinecraftPlayerCertificates().refresh();
            var name = session.getMinecraftProfile().getCached().getName();
            store.update(s -> s.accounts.stream().filter(a -> a.id.equals(accountId)).forEach(a -> {
                a.username = name;
                a.authError = null;
            }));
            writeAuthCache(accountId, session);
            LOG.info("Refreshed login for {}", name);
        } catch (Exception e) {
            store.update(s -> s.accounts.stream().filter(a -> a.id.equals(accountId)).forEach(a -> a.authError = friendlyError(e)));
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /** A pasted access token can't be renewed: when it expires, or 0 for logins that renew themselves. */
    private static long tokenOnlyExpiry(JavaAuthManager session) {
        var msa = session.getMsaToken().getCached();
        if (msa == null || !msa.getRefreshToken().isEmpty()) return 0;
        var token = session.getMinecraftToken().getCached();
        return token != null ? token.getExpireTimeMs() : 1;
    }

    private void writeAuthCache(String accountId, JavaAuthManager session) throws IOException {
        var file = config.accountDir(accountId).resolve(AUTH_CACHE);
        SecureFiles.writeString(file, Json.GSON.toJson(JavaAuthManager.toJson(session)));
        // push the fresh tokens to every instance of this account; they reload the file on each login
        var instanceIds = store.read(s -> s.instances.stream().filter(i -> i.accountId.equals(accountId)).map(i -> i.id).toList());
        for (var id : instanceIds) {
            var dir = config.instanceDir(id);
            if (Files.isDirectory(dir)) SecureFiles.copy(file, dir.resolve(AUTH_CACHE));
        }
    }

    /** Called before an instance starts. */
    public void syncAuthCache(State.Account account, Path instanceDir) throws IOException {
        var target = instanceDir.resolve(AUTH_CACHE);
        if (account.type == State.AccountType.OFFLINE) {
            Files.deleteIfExists(target);
            return;
        }
        var source = config.accountDir(account.id).resolve(AUTH_CACHE);
        if (!Files.exists(source)) throw new IllegalStateException("Account " + account.username + " has no login, log in again");
        SecureFiles.copy(source, target);
    }

    // --- helpers ---

    private void publishLogin(PendingLogin login) {
        events.publish("login", null, login.toJson());
    }

    /** Through the proxy the account's instances use, so Microsoft and Mojang see the same IP as the server. */
    private HttpClient httpClient(String accountId) {
        var client = MinecraftAuth.createHttpClient();
        var relay = proxies != null ? proxies.forAccount(accountId) : null;
        if (relay != null) client.setProxyHandler(new ProxyHandler(ProxyType.HTTP, relay.getHostString(), relay.getPort()));
        return client;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String friendlyError(Exception e) {
        var msg = e.getMessage();
        if (e instanceof java.util.concurrent.TimeoutException) return "The login code expired before it was used";
        if (e instanceof MinecraftProfileNotFoundException) {
            return "This Microsoft account has no Minecraft: Java Edition profile. Buy the game, or launch it once to pick a name.";
        }
        if (msg != null && msg.contains("404")) return "This Microsoft account doesn't own Minecraft, or has never launched it";
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
