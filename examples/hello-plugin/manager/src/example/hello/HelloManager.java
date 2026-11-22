package example.hello;

import com.google.gson.JsonObject;
import com.zenith.manager.plugins.PluginContext;
import com.zenith.manager.plugins.ZenithHubPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The manager side: an API route, and a tally of what the bots report. */
public final class HelloManager implements ZenithHubPlugin {
    private PluginContext ctx;
    private final Map<String, Integer> chat = new ConcurrentHashMap<>();

    @Override
    public void onLoad(PluginContext ctx) {
        this.ctx = ctx;
        ctx.routes(app -> app.get("/api/plugins/hello/summary", http -> {
            var user = ctx.requireUser(http);
            var json = new JsonObject();
            json.addProperty("user", user.username);
            int instances = ctx.hub().store().read(s -> s.instances.size());
            json.addProperty("instances", instances);
            var counts = new JsonObject();
            // only instances the user may see
            chat.forEach((id, n) -> {
                try {
                    ctx.requireCapability(http, id, com.zenith.manager.auth.Capability.VIEW);
                    counts.addProperty(id, n);
                } catch (RuntimeException ignored) {
                    // not theirs
                }
            });
            json.add("chatMessages", counts);
            http.json(json.toString());
        }));
        ctx.onBotEvent((instanceId, event, data) -> {
            if (event.equals("chat")) chat.merge(instanceId, 1, Integer::sum);
            if (event.equals("online") && ctx.settings().get("announce").getAsBoolean()) ctx.logger().info("{} is online", instanceId);
        });
        ctx.logger().info("Hello from the manager");
    }

    @Override
    public void onSettingsChanged(JsonObject settings) {
        ctx.logger().info("New greeting: {}", settings.get("greeting").getAsString());
    }
}
