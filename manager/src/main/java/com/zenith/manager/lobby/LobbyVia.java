package com.zenith.manager.lobby;

import com.viaversion.viabackwards.ViaBackwardsPlatformImpl;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.configuration.RateLimitConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.configuration.AbstractViaConfig;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import com.viaversion.viaversion.platform.ViaCodecHandler;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.channel.Channel;
import org.geysermc.mcprotocollib.network.tcp.TcpPacketCodec;
import org.geysermc.mcprotocollib.network.tcp.TcpServer;
import org.geysermc.mcprotocollib.network.tcp.TcpServerChannelInitializer;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * ViaVersion for the lobby, the same setup Zenith uses for its own server:
 * clients from 1.20.5 (the first version with transfers) up to the newest one
 * Via knows are translated to the lobby's 1.21.4.
 */
final class LobbyVia {
    /** 1.20.5, the first version that can follow a transfer to an instance. */
    static final int MIN_PROTOCOL = 766;

    private LobbyVia() {}

    static void init(Path dataDir) {
        dataDir.toFile().mkdirs();
        ViaManagerImpl.initAndLoad(
            new Platform(dataDir.toFile()),
            new Injector(),
            new ViaCommandHandler(false),
            new Loader(),
            ViaBackwardsPlatformImpl::new
        );
    }

    /** Whether a client with this protocol can use the lobby and follow a transfer. */
    static boolean supports(int protocol) {
        return protocol >= MIN_PROTOCOL && ProtocolVersion.isRegistered(protocol);
    }

    static final TcpServerChannelInitializer.Factory CHANNELS = Channels::new;

    private static final class Channels extends TcpServerChannelInitializer {
        Channels(TcpServer server) {
            super(server);
        }

        @Override
        protected void initChannel(Channel channel) throws Exception {
            super.initChannel(channel);
            var user = new UserConnectionImpl(channel, false);
            new ProtocolPipelineImpl(user);
            channel.pipeline().addBefore(TcpPacketCodec.ID, ViaCodecHandler.NAME, new ViaCodecHandler(user));
        }
    }

    private static final class Platform extends UserConnectionViaVersionPlatform {
        Platform(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public String getPlatformName() {
            return "ZenithProxy Manager";
        }

        @Override
        public String getPlatformVersion() {
            return "1";
        }

        @Override
        protected AbstractViaConfig createConfig() {
            var config = new Config(new File(getDataFolder(), "viaversion.yml"));
            config.reload();
            return config;
        }

        @Override
        public Logger createLogger(String name) {
            return Logger.getLogger(name);
        }

        @Override
        public boolean isProxy() {
            return true;
        }

        @Override
        public boolean kickPlayer(UserConnection connection, String reason) {
            return false; // Via closes the connection itself
        }

        @Override
        public void sendMessage(UserConnection connection, String message) {}

        @Override
        public void sendCustomPayloadToClient(UserConnection connection, String channel, byte[] message) {}

        @Override
        public void sendCustomPayload(UserConnection connection, String channel, byte[] message) {}
    }

    private static final class Injector extends NoopInjector {
        @Override
        public String getEncoderName() {
            return ViaCodecHandler.NAME;
        }

        @Override
        public String getDecoderName() {
            return ViaCodecHandler.NAME;
        }
    }

    private static final class Loader implements ViaPlatformLoader {
        @Override
        public void load() {
            Via.getManager().getProviders().use(VersionProvider.class, connection -> ProtocolVersion.getProtocol(Router.PROTOCOL));
        }

        @Override
        public void unload() {}
    }

    private static final class Config extends AbstractViaConfig {
        private static final RateLimitConfig NO_LIMIT = new RateLimitConfig(false, -1, "", -1, 3, TimeUnit.SECONDS.toNanos(6), "", "");

        Config(File file) {
            super(file, Logger.getLogger("ViaVersion"));
        }

        @Override
        public boolean isCheckForUpdates() {
            return false; // updates come with the ZenithProxy image
        }

        @Override
        public RateLimitConfig getPacketTrackerConfig() {
            return NO_LIMIT;
        }

        @Override
        public RateLimitConfig getPacketSizeTrackerConfig() {
            return NO_LIMIT;
        }
    }
}
