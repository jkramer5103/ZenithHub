package com.zenith.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import com.zenith.feature.queue.mcping.MCPing;
import lombok.experimental.UtilityClass;

import java.util.Comparator;

@UtilityClass
public class ProtocolVersionDetector {
    public int getProtocolVersion(final String hostname, final int port) {
        try {
            final int discovered = MCPing.INSTANCE.getProtocolVersion(hostname, port, 3000, true);
            // Multi-version servers (ViaVersion, proxies) answer a discovery ping with their oldest version, so newer
            // features like 1.21.6 dialogs never reach us. Ask with the newest release we translate and use it if accepted.
            var newest = ProtocolVersion.getProtocols().stream()
                .filter(v -> v.getVersionType() == VersionType.RELEASE && !v.isSnapshot())
                .max(Comparator.comparingInt(ProtocolVersion::getVersion))
                .orElse(null);
            if (newest == null || newest.getVersion() <= discovered) return discovered;
            final int answered = MCPing.INSTANCE.getProtocolVersion(hostname, port, 3000, true, newest.getVersion());
            return answered == newest.getVersion() ? answered : discovered;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
