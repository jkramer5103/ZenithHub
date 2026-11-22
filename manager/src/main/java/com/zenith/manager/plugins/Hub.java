package com.zenith.manager.plugins;

import com.zenith.manager.EventHub;
import com.zenith.manager.ManagerConfig;
import com.zenith.manager.accounts.AccountService;
import com.zenith.manager.auth.AccessService;
import com.zenith.manager.discord.DiscordBot;
import com.zenith.manager.flows.FlowEngine;
import com.zenith.manager.instances.AfkHistory;
import com.zenith.manager.instances.OutboundProxies;
import com.zenith.manager.instances.PearlService;
import com.zenith.manager.instances.PlayerHistory;
import com.zenith.manager.instances.ProxyOverrides;
import com.zenith.manager.instances.ProxyPool;
import com.zenith.manager.instances.SettingsService;
import com.zenith.manager.instances.Supervisor;
import com.zenith.manager.lobby.PlayerDirectory;
import com.zenith.manager.state.StateStore;
import com.zenith.manager.web.WebServer;

/** Every service of the manager, for plugins. The lobby and router start after plugins load and aren't in here. */
public record Hub(
    ManagerConfig config,
    StateStore store,
    EventHub events,
    AccountService accounts,
    Supervisor supervisor,
    SettingsService settings,
    OutboundProxies proxies,
    ProxyPool proxyPool,
    ProxyOverrides proxyOverrides,
    AccessService access,
    PlayerDirectory players,
    PearlService pearls,
    PlayerHistory history,
    AfkHistory afkHistory,
    DiscordBot discord,
    FlowEngine flows,
    WebServer web,
    PluginService plugins
) {}
