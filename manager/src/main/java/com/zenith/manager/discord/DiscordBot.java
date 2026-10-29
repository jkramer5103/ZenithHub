package com.zenith.manager.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.auth.AccessService;
import com.zenith.manager.auth.Capability;
import com.zenith.manager.auth.Permissions;
import com.zenith.manager.instances.InstanceRuntime;
import com.zenith.manager.instances.PearlService;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.state.State;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.util.SecureFiles;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.role.GenericRoleEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The optional central Discord bot. One category per account and one channel per instance,
 * visible to whoever may view that instance. Commands use the same grants as the web UI.
 */
public final class DiscordBot extends ListenerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger("Discord");
    private static final EnumSet<Permission> MEMBER_ALLOW = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
    private static final EnumSet<Permission> BOT_ALLOW = EnumSet.of(
        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY,
        Permission.MANAGE_CHANNEL);
    private static final List<Permission> INVITE_PERMISSIONS = List.of(
        Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
        Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);
    private static final Duration LINK_CODE_LIFETIME = Duration.ofMinutes(10);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public enum Phase { OFF, CONNECTING, ONLINE, ERROR }

    private record LinkCode(String userId, long expiresAt) {}

    private final Path tokenFile;
    private final StateStore store;
    private final AccessService access;
    private final Supervisor supervisor;
    private final PearlService pearls;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("discord").daemon().factory());
    private final Map<String, LinkCode> linkCodes = new ConcurrentHashMap<>();
    private final Map<String, String> lastPhase = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile JDA jda;
    private volatile Phase phase = Phase.OFF;
    private volatile String error;
    private ScheduledFuture<?> pendingSync;

    public DiscordBot(ManagerConfig config, StateStore store, AccessService access, Supervisor supervisor, EventHub events, PearlService pearls) {
        this.pearls = pearls;
        this.tokenFile = config.dataDir().resolve("manager").resolve("secrets").resolve("discord-token");
        this.store = store;
        this.access = access;
        this.supervisor = supervisor;
        access.onChange(id -> scheduleSync());
        events.subscribe(this::onEvent);
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
    }

    // --- lifecycle ---

    public synchronized void start() {
        if (jda != null || !store.read(s -> s.settings.discordEnabled)) return;
        var token = readToken();
        if (token == null) return;
        phase = Phase.CONNECTING;
        error = null;
        Thread.ofVirtual().name("discord-login").start(() -> {
            try {
                var bot = JDABuilder.createLight(token, List.of()).addEventListeners(this).build();
                synchronized (this) {
                    jda = bot;
                }
                bot.awaitReady();
                phase = Phase.ONLINE;
                LOG.info("Discord bot online as {}", bot.getSelfUser().getName());
                if (bot.getGuilds().isEmpty()) LOG.info("Invite the bot to your Discord server: {}", inviteUrl(bot));
            } catch (InvalidTokenException e) {
                fail("Discord rejected the token");
            } catch (IllegalStateException e) {
                fail(e.getMessage());
            } catch (Exception e) {
                fail("Couldn't connect: " + e.getMessage());
            }
        });
    }

    private synchronized void fail(String message) {
        LOG.warn("Discord bot: {}", message);
        if (jda != null) jda.shutdownNow();
        jda = null;
        phase = Phase.ERROR;
        error = message;
    }

    public synchronized void stop() {
        if (jda != null) jda.shutdown();
        jda = null;
        phase = Phase.OFF;
        error = null;
    }

    public void restart() {
        stop();
        start();
    }

    public void setToken(String token) throws IOException {
        Files.createDirectories(tokenFile.getParent());
        SecureFiles.writeString(tokenFile, token.trim());
    }

    public void clearToken() throws IOException {
        Files.deleteIfExists(tokenFile);
    }

    private String readToken() {
        try {
            if (!Files.exists(tokenFile)) return null;
            var token = Files.readString(tokenFile).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    // JDA only asks for the bot scope; slash commands need applications.commands too
    private static String inviteUrl(JDA bot) {
        return bot.getInviteUrl(INVITE_PERMISSIONS).replace("scope=bot", "scope=bot+applications.commands");
    }

    /** For the admin settings page. Never includes the token. */
    public JsonObject status() {
        var json = new JsonObject();
        json.addProperty("configured", readToken() != null);
        json.addProperty("enabled", (Boolean) store.read(s -> s.settings.discordEnabled));
        json.addProperty("phase", phase.name());
        json.addProperty("error", error);
        var bot = jda;
        if (bot != null && phase == Phase.ONLINE) {
            json.addProperty("botName", bot.getSelfUser().getName());
            json.addProperty("inviteUrl", inviteUrl(bot));
            var guild = guild();
            json.addProperty("serverName", guild != null ? guild.getName() : null);
            var missing = new JsonArray();
            if (guild != null) {
                var have = guild.getSelfMember().getPermissions();
                if (!have.contains(Permission.ADMINISTRATOR))
                    INVITE_PERMISSIONS.stream().filter(p -> !have.contains(p)).forEach(p -> missing.add(p.getName()));
            }
            json.add("missingPermissions", missing);
        }
        return json;
    }

    public boolean online() {
        return phase == Phase.ONLINE;
    }

    // --- linking ---

    /** A short code the user types as /link CODE in Discord. */
    public String createLinkCode(String userId) {
        linkCodes.values().removeIf(c -> c.userId().equals(userId) || c.expiresAt() < System.currentTimeMillis());
        var code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        linkCodes.put(code.toString(), new LinkCode(userId, System.currentTimeMillis() + LINK_CODE_LIFETIME.toMillis()));
        return code.toString();
    }

    // --- Discord events ---

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        if (event.getGuild() != guild()) return;
        registerCommands(event.getGuild());
        scheduleSync();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (event.getGuild() != guild()) return;
        registerCommands(event.getGuild());
        scheduleSync();
    }

    // the bot's role may get its permissions after joining
    @Override
    public void onGenericRole(GenericRoleEvent event) {
        if (event.getGuild() == guild()) scheduleSync();
    }

    private void registerCommands(Guild guild) {
        guild.updateCommands().addCommands(
            Commands.slash("link", "Link your Discord account to your ZenithHub login")
                .addOption(OptionType.STRING, "code", "The code shown in the web UI", true),
            Commands.slash("status", "Show this instance's status"),
            Commands.slash("connect", "Connect this instance to its server"),
            Commands.slash("disconnect", "Disconnect this instance and shut it down"),
            Commands.slash("pearl", "Load a stasis pearl with this instance's bot")
                .addOption(OptionType.STRING, "pearl", "Whose pearl", true, true),
            Commands.slash("command", "Run a ZenithProxy command on this instance")
                .addOption(OptionType.STRING, "command", "The command, without a prefix", true)
        ).queue(null, e -> LOG.warn("Couldn't register commands: {}", e.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        var name = event.getName();
        LOG.info("/{} from {} in #{}", name, event.getUser().getName(), event.getChannel().getName());
        if (name.equals("link")) {
            link(event);
            return;
        }
        var instance = store.read(s -> s.instances.stream()
            .filter(i -> event.getChannel().getId().equals(i.discordChannelId))
            .findFirst()
            .map(i -> new String[]{i.id, i.name})
            .orElse(null));
        if (instance == null) {
            event.reply("Use this in an instance's channel.").setEphemeral(true).queue();
            return;
        }
        var who = access.identityOfDiscord(event.getUser().getId());
        var caps = store.read(s -> s.instances.stream().filter(i -> i.id.equals(instance[0])).findFirst()
            .map(i -> Permissions.capabilities(who, i)).orElse(EnumSet.noneOf(Capability.class)));
        var needed = switch (name) {
            case "status" -> Capability.VIEW;
            case "connect", "disconnect" -> Capability.CONTROL;
            case "pearl" -> Capability.PEARLS;
            default -> Capability.COMMANDS;
        };
        if (!caps.contains(needed)) {
            event.reply("You don't have permission to do that here.").setEphemeral(true).queue();
            return;
        }
        var id = instance[0];
        var actor = event.getUser().getName() + " (Discord)";
        event.deferReply().queue();
        Thread.ofVirtual().name("discord-command").start(() -> {
            try {
                switch (name) {
                    case "status" -> {
                        // Zenith's own status embed while it runs, like its own bot shows
                        if (supervisor.runtime(id).phase() == InstanceRuntime.Phase.RUNNING) {
                            try {
                                replyWithOutput(event, supervisor.command(id, "status", actor), ownerLine(id));
                                return;
                            } catch (Exception ignored) {
                                // fall back to the manager's view
                            }
                        }
                        event.getHook().sendMessageEmbeds(statusEmbed(id, instance[1]).build()).queue();
                    }
                    case "connect" -> {
                        long since = System.currentTimeMillis();
                        supervisor.connect(id, actor);
                        // interaction tokens last 15 minutes, plenty for a slow start
                        var failure = supervisor.awaitConnected(id, since, Duration.ofMinutes(2));
                        event.getHook().sendMessage(failure == null ? "Connected."
                            : failure.equals("still connecting") ? "Still connecting. Use /status to check."
                            : "Couldn't connect: " + failure).queue();
                    }
                    case "disconnect" -> {
                        supervisor.disconnect(id, actor);
                        event.getHook().sendMessage("Disconnected.").queue();
                    }
                    case "pearl" -> {
                        var result = pearls.load(id, event.getOption("pearl").getAsString().trim(), actor);
                        event.getHook().sendMessage(result.message()).queue();
                    }
                    default -> {
                        var command = name.equals("command") ? event.getOption("command").getAsString().trim() : name;
                        replyWithOutput(event, supervisor.command(id, command, actor), null);
                    }
                }
            } catch (Exception e) {
                event.getHook().sendMessage(e.getMessage() != null ? e.getMessage() : "That didn't work.").queue();
            }
        });
    }

    /** Pearl names for /pearl, only for people who may load them. */
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("pearl")) return;
        var instanceId = store.read(s -> s.instances.stream()
            .filter(i -> event.getChannel() != null && event.getChannel().getId().equals(i.discordChannelId))
            .filter(i -> Permissions.has(access.identityOfDiscord(event.getUser().getId()), i, Capability.PEARLS))
            .findFirst().map(i -> i.id).orElse(null));
        if (instanceId == null) {
            event.replyChoices(List.of()).queue();
            return;
        }
        Thread.ofVirtual().name("discord-autocomplete").start(() -> {
            var typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
            var choices = pearls.pearls(instanceId).stream()
                .map(PearlService.Pearl::id)
                .filter(p -> p.toLowerCase(Locale.ROOT).contains(typed))
                .limit(25)
                .map(p -> new Command.Choice(p, p))
                .toList();
            event.replyChoices(choices).queue(null, e -> { });
        });
    }

    private void link(SlashCommandInteractionEvent event) {
        var code = event.getOption("code").getAsString().trim().toUpperCase(Locale.ROOT);
        var pending = linkCodes.remove(code);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            event.reply("That code is invalid or expired. Get a new one in the web UI.").setEphemeral(true).queue();
            return;
        }
        try {
            access.linkDiscord(pending.userId(), event.getUser().getId());
            var username = store.read(s -> s.users.stream().filter(u -> u.id.equals(pending.userId())).findFirst().map(u -> u.username).orElse("?"));
            event.reply("Linked to " + username + ". You'll now see the channels of instances shared with you.").setEphemeral(true).queue();
        } catch (Exception e) {
            event.reply("Couldn't link: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void replyWithOutput(SlashCommandInteractionEvent event, JsonObject result, String footer) {
        var embedJson = result.has("embed") && result.get("embed").isJsonObject() ? result.getAsJsonObject("embed") : null;
        var lines = new ArrayList<String>();
        if (result.has("lines")) result.getAsJsonArray("lines").forEach(l -> lines.add(l.getAsString()));
        var embed = embedJson != null ? toEmbed(embedJson) : null;
        if (embed != null && footer != null) embed.setFooter(footer);
        var built = embed != null && !embed.isEmpty() ? embed.build() : null;
        if (!lines.isEmpty()) {
            var text = "```\n" + truncate(String.join("\n", lines), 1800) + "\n```";
            if (built != null) event.getHook().sendMessage(text).addEmbeds(built).queue();
            else event.getHook().sendMessage(text).queue();
        } else if (built != null) {
            event.getHook().sendMessageEmbeds(built).queue();
        } else {
            event.getHook().sendMessage("Done.").queue();
        }
    }

    /** Zenith's embed JSON as a Discord embed, the way Zenith's own bot would send it. */
    private static EmbedBuilder toEmbed(JsonObject json) {
        var embed = new EmbedBuilder();
        if (str(json, "title") != null) embed.setTitle(truncate(str(json, "title"), 256), validUrl(str(json, "url")));
        if (str(json, "description") != null) embed.setDescription(truncate(str(json, "description"), 4000));
        if (json.has("color") && !json.get("color").isJsonNull()) embed.setColor(json.get("color").getAsInt() & 0xFFFFFF);
        if (json.has("timestamp") && !json.get("timestamp").isJsonNull()) embed.setTimestamp(Instant.ofEpochMilli(json.get("timestamp").getAsLong()));
        if (validUrl(str(json, "image")) != null) embed.setImage(str(json, "image"));
        if (validUrl(str(json, "thumbnail")) != null) embed.setThumbnail(str(json, "thumbnail"));
        if (json.has("footer") && json.get("footer").isJsonObject()) {
            var footer = json.getAsJsonObject("footer");
            var text = str(footer, "text");
            if (text != null && !text.isEmpty()) embed.setFooter(truncate(text, 2048), validUrl(str(footer, "iconUrl")));
        }
        if (json.has("author") && json.get("author").isJsonObject()) {
            var author = json.getAsJsonObject("author");
            if (str(author, "name") != null) embed.setAuthor(truncate(str(author, "name"), 256), validUrl(str(author, "url")), validUrl(str(author, "iconUrl")));
        }
        if (json.has("fields")) {
            for (var f : json.getAsJsonArray("fields")) {
                var field = f.getAsJsonObject();
                if (embed.getFields().size() >= 25) break;
                embed.addField(truncate(str(field, "name"), 256), truncate(str(field, "value"), 1024), field.has("inline") && field.get("inline").getAsBoolean());
            }
        }
        return embed;
    }

    private static String validUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://")) ? url : null;
    }

    /** "Owner: alice", so it's always clear whose account an instance is. */
    private String ownerLine(String instanceId) {
        return store.read(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst()
            .map(i -> "Owner: " + ownerName(s, i)).orElse(null));
    }

    private static String ownerName(State s, State.Instance instance) {
        return s.users.stream().filter(u -> u.id.equals(instance.ownerId)).findFirst().map(u -> u.username).orElse("nobody");
    }

    private EmbedBuilder statusEmbed(String id, String name) {
        var rt = supervisor.runtime(id);
        var embed = new EmbedBuilder().setTitle(name).setFooter(ownerLine(id));
        if (rt.phase() == InstanceRuntime.Phase.STARTING) return embed.setDescription("Connecting…").setColor(0xf59e0b);
        var status = rt.phase() == InstanceRuntime.Phase.RUNNING ? rt.status() : null;
        boolean connected = status != null && status.has("connected") && status.get("connected").getAsBoolean();
        if (!connected) {
            embed.setDescription("Offline").setColor(0x71717a);
            if (status != null && status.has("lastDisconnectReason")) embed.addField("Last disconnect", str(status, "lastDisconnectReason"), false);
            return embed;
        }
        boolean inQueue = status.has("inQueue") && status.get("inQueue").getAsBoolean();
        embed.setColor(inQueue ? 0xf59e0b : 0x10b981);
        embed.setDescription(inQueue ? "In queue, position " + status.get("queuePosition").getAsInt() : "Online");
        if (status.has("server")) embed.addField("Server", str(status, "server"), true);
        if (status.has("health")) embed.addField("Health", String.valueOf(Math.round(status.get("health").getAsDouble())), true);
        if (status.has("controller") && !status.get("controller").isJsonNull()) embed.addField("Playing", str(status, "controller"), true);
        return embed;
    }

    // --- relaying instance events ---

    private void onEvent(EventHub.Event event) {
        if (!online() || event.instanceId() == null) {
            if ("changed".equals(event.type())) scheduleSync();
            return;
        }
        switch (event.type()) {
            // what Zenith's own bot would have posted in its main channel
            case "notification" -> {
                var payload = event.payload();
                var embed = payload.has("embed") && payload.get("embed").isJsonObject() ? toEmbed(payload.getAsJsonObject("embed")) : null;
                boolean mention = payload.has("mention") && payload.get("mention").getAsBoolean();
                post(event.instanceId(), str(payload, "text"), embed != null && !embed.isEmpty() ? embed.build() : null, mention);
            }
            case "runtime" -> {
                // Zenith reports connects and disconnects itself; only crashes are the manager's to tell
                var phase = str(event.payload(), "phase");
                if (phase == null || phase.equals(lastPhase.put(event.instanceId(), phase))) return;
                if (phase.equals("BACKOFF")) {
                    var error = str(event.payload(), "lastError");
                    var embed = new EmbedBuilder()
                        .setTitle("Instance crashed")
                        .setDescription(error != null ? truncate(error, 4000) : "It restarts by itself shortly.")
                        .setColor(0xE74C3C)
                        .setTimestamp(Instant.now());
                    post(event.instanceId(), null, embed.build(), true);
                }
            }
            default -> {}
        }
    }

    /** Posts to an instance's channel; a mention pings the account owner if they linked Discord. */
    private void post(String instanceId, String text, MessageEmbed embed, boolean mention) {
        var channel = channel(instanceId);
        if (channel == null || (text == null && embed == null && !mention)) return;
        String ownerDiscord = mention ? store.read(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst()
            .flatMap(i -> s.users.stream().filter(u -> u.id.equals(i.ownerId)).findFirst())
            .map(u -> u.discordId).orElse(null)) : null;
        var content = new StringBuilder();
        if (ownerDiscord != null) content.append("<@").append(ownerDiscord).append("> ");
        if (text != null) content.append(truncate(text, 1800));
        var message = new MessageCreateBuilder().setContent(content.toString().strip());
        if (embed != null) message.setEmbeds(embed);
        if (ownerDiscord != null) message.mentionUsers(ownerDiscord);
        if (message.isEmpty()) return;
        channel.sendMessage(message.build()).queue(null, e -> LOG.debug("Couldn't post to {}: {}", channel.getId(), e.getMessage()));
    }

    private TextChannel channel(String instanceId) {
        var guild = guild();
        if (guild == null) return null;
        var channelId = store.read(s -> s.instances.stream().filter(i -> i.id.equals(instanceId)).findFirst()
            .map(i -> i.discordChannelId).orElse(null));
        return channelId != null ? guild.getTextChannelById(channelId) : null;
    }

    // --- channels and visibility ---

    /** The bot serves one Discord server: the first it was invited to. */
    private Guild guild() {
        var bot = jda;
        if (bot == null) return null;
        var guilds = bot.getGuilds();
        return guilds.isEmpty() ? null : guilds.getFirst();
    }

    private synchronized void scheduleSync() {
        if (pendingSync != null) pendingSync.cancel(false);
        // coalesce bursts of changes
        pendingSync = worker.schedule(this::sync, 2, TimeUnit.SECONDS);
    }

    private void sync() {
        var guild = guild();
        if (guild == null || !online()) return;
        try {
            var self = guild.getSelfMember().getIdLong();
            var everyone = guild.getPublicRole().getIdLong();
            var snapshot = store.read(s -> new Snapshot(List.copyOf(s.accounts), List.copyOf(s.instances), List.copyOf(s.users)));
            var keptChannels = new HashSet<String>();
            var categories = new HashMap<String, Category>();
            for (var account : snapshot.accounts()) {
                if (snapshot.instances().stream().noneMatch(i -> account.id.equals(i.accountId))) continue;
                // whose account it is, since one Discord server shows everyone's
                var owner = snapshot.users().stream().filter(u -> u.id.equals(account.ownerId)).findFirst().map(u -> u.username).orElse(null);
                var categoryName = truncate(owner != null ? account.username + " · " + owner : account.username, 100);
                var category = account.discordCategoryId != null ? guild.getCategoryById(account.discordCategoryId) : null;
                if (category == null) {
                    category = guild.createCategory(categoryName)
                        .addRolePermissionOverride(everyone, null, EnumSet.of(Permission.VIEW_CHANNEL))
                        .addMemberPermissionOverride(self, BOT_ALLOW, null)
                        .complete();
                    var categoryId = category.getId();
                    store.update(s -> s.accounts.stream().filter(a -> a.id.equals(account.id)).forEach(a -> a.discordCategoryId = categoryId));
                } else if (!category.getName().equals(categoryName)) {
                    category.getManager().setName(categoryName).complete();
                }
                categories.put(account.id, category);
            }
            for (var instance : snapshot.instances()) {
                var category = categories.get(instance.accountId);
                if (category == null) continue;
                var owner = snapshot.users().stream().filter(u -> u.id.equals(instance.ownerId)).findFirst().map(u -> u.username).orElse("nobody");
                var main = syncChannel(guild, category, instance.discordChannelId, channelName(instance.name),
                    "ZenithHub instance " + instance.name + ", owned by " + owner + ". Notifications land here. Use /status, /connect and /command.",
                    id -> store.update(s -> s.instances.stream().filter(i -> i.id.equals(instance.id)).forEach(i -> i.discordChannelId = id)),
                    everyone, self);
                keptChannels.add(main.getId());
                syncViewers(main, instance, snapshot.users(), everyone, self);
            }
            // channels of deleted instances
            for (var category : categories.values()) {
                for (var channel : category.getTextChannels()) {
                    if (!keptChannels.contains(channel.getId())) channel.delete().queue();
                }
            }
        } catch (Exception e) {
            LOG.warn("Couldn't update Discord channels: {}", e.getMessage());
        }
    }

    private TextChannel syncChannel(Guild guild, Category category, String channelId, String name, String topic,
                                    java.util.function.Consumer<String> remember, long everyone, long self) {
        var channel = channelId != null ? guild.getTextChannelById(channelId) : null;
        if (channel == null) {
            channel = guild.createTextChannel(name, category)
                .setTopic(topic)
                .addRolePermissionOverride(everyone, null, EnumSet.of(Permission.VIEW_CHANNEL))
                .addMemberPermissionOverride(self, BOT_ALLOW, null)
                .complete();
            remember.accept(channel.getId());
            return channel;
        }
        var manager = channel.getManager();
        boolean changed = false;
        if (!channel.getName().equals(name)) {
            manager = manager.setName(name);
            changed = true;
        }
        if (!topic.equals(channel.getTopic())) {
            manager = manager.setTopic(topic);
            changed = true;
        }
        if (channel.getParentCategoryIdLong() != category.getIdLong()) {
            manager = manager.setParent(category);
            changed = true;
        }
        if (changed) manager.complete();
        return channel;
    }

    private record Snapshot(List<State.Account> accounts, List<State.Instance> instances, List<State.User> users) {}

    private void syncViewers(TextChannel channel, State.Instance instance, List<State.User> users, long everyone, long self) {
        var viewers = new HashSet<Long>();
        for (var user : users) {
            if (user.discordId == null) continue;
            if (Permissions.has(Permissions.Identity.of(user), instance, Capability.VIEW)) viewers.add(Long.parseLong(user.discordId));
        }
        for (var grant : instance.grants) {
            if (grant.subject.startsWith("discord:") && AccessService.grantCapabilities(grant).contains(Capability.VIEW)) {
                viewers.add(Long.parseLong(grant.subject.substring(8)));
            }
        }
        var manager = channel.getManager()
            .putRolePermissionOverride(everyone, null, EnumSet.of(Permission.VIEW_CHANNEL))
            .putMemberPermissionOverride(self, BOT_ALLOW, null);
        boolean changed = false;
        Set<Long> existing = new HashSet<>();
        for (var override : channel.getMemberPermissionOverrides()) {
            long id = override.getIdLong();
            existing.add(id);
            if (id != self && !viewers.contains(id)) {
                manager = manager.removePermissionOverride(id);
                changed = true;
            }
        }
        for (var id : viewers) {
            if (!existing.contains(id)) {
                manager = manager.putMemberPermissionOverride(id, MEMBER_ALLOW, null);
                changed = true;
            }
        }
        if (changed || !existing.contains(self)) manager.complete();
    }

    private static String channelName(String name) {
        var slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "instance" : slug.length() > 90 ? slug.substring(0, 90) : slug;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String str(JsonObject json, String key) {
        var e = json.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
