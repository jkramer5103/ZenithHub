package com.zenith.manager;

import com.zenith.manager.accounts.AccountService;
import com.zenith.manager.auth.AccessService;
import com.zenith.manager.discord.DiscordBot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zenith.manager.instances.ConfigComposer;
import com.zenith.manager.instances.OutboundProxies;
import com.zenith.manager.instances.PearlService;
import com.zenith.manager.instances.PlayerHistory;
import com.zenith.manager.instances.SettingsService;
import com.zenith.manager.lobby.LobbyServer;
import com.zenith.manager.lobby.PlayerDirectory;
import com.zenith.manager.lobby.Router;
import com.zenith.manager.util.Json;
import com.zenith.manager.util.MojangProfiles;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.List;

public final class ManagerMain {
    private static final Logger LOG = LoggerFactory.getLogger("Manager");

    public static void main(String[] args) throws Exception {
        var config = ManagerConfig.fromEnv();
        LOG.info("ZenithHub starting, data directory: {}", config.dataDir());
        Files.createDirectories(config.dataDir().resolve("manager"));

        var store = new StateStore(config.stateFile());
        var events = new EventHub();
        var accounts = new AccountService(config, store, events);
        var supervisor = new Supervisor(config, store, accounts, events);
        var settings = new SettingsService(config, store, supervisor);
        var proxies = new OutboundProxies(config, store, supervisor);
        accounts.setProxies(proxies);
        MojangProfiles.setProxy(proxies::global);
        var access = new AccessService(store);
        var players = new PlayerDirectory(config.dataDir().resolve("manager").resolve("players.json"));
        supervisor.setJoinLists(id -> joinLists(access.joinNames(id), players));
        // grants decide who may join; running instances get the new lists right away
        access.onChange(id -> Thread.ofVirtual().start(() -> pushJoinLists(store, supervisor, id)));
        // access can change between composing a new process's config and the process answering
        supervisor.setOnRunning(id -> Thread.ofVirtual().start(() -> pushJoinLists(store, supervisor, id)));
        players.onLearned(name -> Thread.ofVirtual().start(() -> pushJoinLists(store, supervisor, null)));
        var pearls = new PearlService(config, supervisor, settings);
        var history = new PlayerHistory(config);
        supervisor.setPlayerEvents(history::record);
        var discord = new DiscordBot(config, store, access, supervisor, events, pearls);
        var web = new WebServer(config, store, accounts, supervisor, events, settings, access, discord, pearls, history);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping instances...");
            discord.stop();
            supervisor.shutdownAll();
            web.stop();
        }, "shutdown"));

        web.start();
        var lobby = new LobbyServer(store, access, supervisor, players);
        var router = new Router(config.lobbyPort(), lobby.start(config.lobbyOnlineMode(), config.dataDir().resolve("manager").resolve("via")), id -> supervisor.isRunning(id)
            ? store.read(s -> s.instances.stream().filter(i -> i.id.equals(id)).findFirst().map(i -> i.mcPort).orElse(null))
            : null);
        router.start();
        discord.start();
        accounts.startBackgroundRefresh();
        supervisor.startBackgroundTasks();
        // instances are composed from the Zenith defaults, so read them first in case the image was updated
        Thread.ofVirtual().name("startup").start(() -> {
            settings.refreshSchema();
            supervisor.restorePreviousState();
        });
        if (store.read(s -> s.users.isEmpty())) {
            LOG.info("First run: open the web UI to create the admin account");
        }
    }

    private static ConfigComposer.JoinLists joinLists(AccessService.JoinNames names, PlayerDirectory players) {
        return new ConfigComposer.JoinLists(entries(names.players(), players), entries(names.spectators(), players));
    }

    private static JsonArray entries(List<String> names, PlayerDirectory players) {
        var arr = new JsonArray();
        for (var name : names) {
            var player = players.get(name);
            if (player != null) arr.add(PlayerDirectory.entry(player));
        }
        return arr;
    }

    private static void pushJoinLists(StateStore store, Supervisor supervisor, String onlyInstance) {
        var ids = store.read(s -> s.instances.stream().map(i -> i.id).toList());
        for (var id : ids) {
            if (onlyInstance != null && !onlyInstance.equals(id)) continue;
            if (!supervisor.isRunning(id)) continue;
            try {
                var forced = ConfigComposer.forcedValues(supervisor.context(id));
                var patch = new JsonObject();
                for (var path : List.of("server.extra.whitelist.whitelist", "server.spectator.whitelist", "server.proxyIP")) {
                    Json.set(patch, path, forced.get(path));
                }
                supervisor.pushConfig(id, patch);
            } catch (Exception e) {
                LOG.warn("Couldn't update who may join {}: {}", id, e.getMessage());
            }
        }
    }
}
