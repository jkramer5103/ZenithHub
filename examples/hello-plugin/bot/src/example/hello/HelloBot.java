package example.hello;

import com.google.gson.JsonObject;
import com.zenith.control.plugins.BotPluginContext;
import com.zenith.control.plugins.ZenithHubBotPlugin;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.client.ClientOnlineEvent;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.EVENT_BUS;
import static com.zenith.Globals.TPS;

/** The bot side: listens to ZenithProxy's event bus, answers a route and reports events to the manager. */
public final class HelloBot implements ZenithHubBotPlugin {
    private final AtomicInteger chat = new AtomicInteger();
    private volatile String greeting = "Hello";

    @Override
    public void onLoad(BotPluginContext ctx) {
        greeting = ctx.settings().get("greeting").getAsString();
        EVENT_BUS.subscribe(this,
            of(ClientOnlineEvent.class, e -> ctx.emit("online", new JsonObject())),
            of(PublicChatEvent.class, e -> {
                chat.incrementAndGet();
                var data = new JsonObject();
                data.addProperty("message", e.message());
                ctx.emit("chat", data);
            }));
        ctx.route("stats", request -> {
            var json = new JsonObject();
            json.addProperty("greeting", greeting + " from " + ctx.instanceId());
            json.addProperty("chatMessages", chat.get());
            json.addProperty("tps", TPS.getTPS());
            return json;
        });
        ctx.logger().info("Hello from the bot");
    }

    @Override
    public void onSettingsChanged(JsonObject settings) {
        greeting = settings.get("greeting").getAsString();
    }
}
