package com.zenith.control;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.SimpleNameResolver;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.CLIENT_LOG;

/**
 * ZenithHub: host lookups that survive resolvers which answer the A query for a CNAME with nothing.
 * <p>
 * HugoSMP's SRV record points at {@code java.hugosmp.net}, a CNAME on Cloudflare, and some home routers
 * (Telekom Speedport) answer its A query with an empty NOERROR while answering the CNAME query fine, so the
 * system lookup fails with {@link UnknownHostException}. When that happens we ask for the CNAME ourselves and
 * look up its target. The returned address keeps the original host name, which the handshake still sends.
 */
public final class HostResolver {
    private static final int MAX_CNAME_HOPS = 8;
    private static final EventLoopGroup EVENT_LOOP_GROUP =
        new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("dns-cname", true), NioIoHandler.newFactory());

    /** For netty bootstraps: an unresolved remote address is looked up with {@link #resolve}. */
    public static final AddressResolverGroup<InetSocketAddress> RESOLVER_GROUP = new AddressResolverGroup<>() {
        @Override
        protected AddressResolver<InetSocketAddress> newResolver(final EventExecutor executor) {
            return new InetSocketAddressResolver(executor, new SimpleNameResolver<>(executor) {
                @Override
                protected void doResolve(final String host, final Promise<InetAddress> promise) {
                    try {
                        promise.setSuccess(HostResolver.resolve(host));
                    } catch (final Exception e) {
                        promise.setFailure(e);
                    }
                }

                @Override
                protected void doResolveAll(final String host, final Promise<List<InetAddress>> promise) {
                    try {
                        promise.setSuccess(List.of(HostResolver.resolve(host)));
                    } catch (final Exception e) {
                        promise.setFailure(e);
                    }
                }
            });
        }
    };

    private HostResolver() {}

    public static InetAddress resolve(final String host) throws UnknownHostException {
        try {
            return InetAddress.getByName(host);
        } catch (final UnknownHostException e) {
            String name = host;
            for (int i = 0; i < MAX_CNAME_HOPS; i++) {
                name = cname(name);
                if (name == null) break;
                try {
                    var address = InetAddress.getByName(name);
                    CLIENT_LOG.info("Resolved {} through its CNAME {} -> {}", host, name, address.getHostAddress());
                    return InetAddress.getByAddress(host, address.getAddress());
                } catch (final UnknownHostException ignored) {
                    // the target may be another CNAME the resolver doesn't follow either
                }
            }
            throw e;
        }
    }

    private static String cname(final String name) {
        AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = null;
        try (DnsNameResolver resolver = new DnsNameResolverBuilder(EVENT_LOOP_GROUP.next())
            .datagramChannelType(NioDatagramChannel.class)
            .build()) {
            envelope = resolver.query(new DefaultDnsQuestion(name, DnsRecordType.CNAME)).get(5, TimeUnit.SECONDS);
            DnsResponse response = envelope.content();
            for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
                DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                if (record.type() == DnsRecordType.CNAME && record instanceof DnsRawRecord raw) {
                    ByteBuf buf = raw.content().duplicate();
                    String target = DefaultDnsRecordDecoder.decodeName(buf);
                    return target.endsWith(".") ? target.substring(0, target.length() - 1) : target;
                }
            }
            return null;
        } catch (final Exception e) {
            CLIENT_LOG.debug("CNAME lookup for {} failed", name, e);
            return null;
        } finally {
            if (envelope != null) envelope.release();
        }
    }
}
