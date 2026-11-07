/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.discord;

import com.neovisionaries.ws.client.WebSocketFactory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Discord channel adapter. Connects to the Discord Gateway via a JDA WebSocket long-connection — no
 * public callback URL is required.
 *
 * <h2>Behaviour</h2>
 *
 * <ul>
 *   <li>Guild channels: when {@link DiscordChannelProperties#onlyAtReply() onlyAtReply} is {@code
 *       true} (default), only messages that @-mention the bot are processed; DMs are always
 *       responded to.
 *   <li>Bot and webhook messages are ignored (loop prevention via {@link BotLoopGuard}).
 *   <li>Outbound replies are split at the 2000-character Discord limit and sent with exponential
 *       backoff retry on transient failures.
 *   <li>Identity: {@code senderId} is fixed to the channel type name ({@code "discord"}) so that
 *       sandbox state and session indexing receive a stable, recognisable identity.
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * channels:
 *   my-discord:
 *     type: discord
 *     routing:
 *       defaultAgentId: main
 *     properties:
 *       botToken: "your-bot-token"
 *       onlyAtReply: true
 *       proxyHost: "127.0.0.1"   # optional HTTP CONNECT proxy for the Gateway WS
 *       proxyPort: 7890           # optional, defaults to 7890
 * }</pre>
 */
public final class DiscordChannel extends ListenerAdapter implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "discord";

    /**
     * Channel-link identity: all inbound messages carry this as {@code senderId} so that sandbox
     * state and session index slots are stable per channel type.
     */
    private static final String CHANNEL_IDENTITY = TYPE;

    private final String channelId;
    private final ChannelConfig config;
    private final DiscordChannelProperties properties;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /** Resolved target → {@link MessageChannel} cache shared with the outbound client. */
    private final Map<String, MessageChannel> knownChannels = new ConcurrentHashMap<>();

    private final ScheduledExecutorService retryExecutor;

    private volatile Gateway gateway;
    private volatile JDA jda;

    private DiscordChannel(
            String channelId,
            ChannelConfig config,
            DiscordChannelProperties properties,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            ScheduledExecutorService retryExecutor) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
    }

    /**
     * Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}.
     *
     * @param channelId logical channel id from {@code agentscope.json}
     * @param routing routing configuration (bindings, defaultAgentId, dmScope)
     * @param rawProperties provider-specific properties (botToken, onlyAtReply, proxyHost, …)
     */
    public static DiscordChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        DiscordChannelProperties props = DiscordChannelProperties.from(channelId, rawProperties);
        return create(channelId, routing, props);
    }

    /**
     * Typed factory for callers that already hold strongly-typed configuration values (e.g. from a
     * database row rather than JSON deserialisation). Equivalent to {@link #fromProperties} but
     * skips the {@code Map<String, Object>} round-trip.
     *
     * @param channelId logical channel id
     * @param routing routing configuration (bindings, defaultAgentId, dmScope)
     * @param botToken Discord bot token
     * @param onlyAtReply when {@code true}, guild messages require an @-mention; DMs always respond
     * @param proxyHost optional HTTP CONNECT proxy host for the Gateway WebSocket; {@code null} for
     *     direct connection
     * @param proxyPort proxy port; ignored when {@code proxyHost} is {@code null}
     */
    public static DiscordChannel create(
            String channelId,
            ChannelConfig routing,
            String botToken,
            boolean onlyAtReply,
            String proxyHost,
            int proxyPort) {
        DiscordChannelProperties props =
                new DiscordChannelProperties(botToken, onlyAtReply, proxyHost, proxyPort);
        return create(channelId, routing, props);
    }

    private static DiscordChannel create(
            String channelId, ChannelConfig routing, DiscordChannelProperties props) {
        BotLoopGuard guard = new BotLoopGuard();
        ChannelRouter router = new ChannelRouter(routing.defaultAgentId());
        ScheduledExecutorService retryExec = DiscordOutboundClient.createRetryExecutor();
        return new DiscordChannel(channelId, routing, props, guard, router, retryExec);
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    /**
     * Establishes the Discord Gateway WebSocket connection via JDA. Non-blocking — the ready event
     * is logged asynchronously.
     *
     * <p>If {@link DiscordChannelProperties#proxyHost()} is set, an HTTP CONNECT proxy is configured
     * on the underlying nv-websocket-client (JDA's WS library does not honour {@code
     * -Dhttps.proxyHost}).
     */
    @Override
    public void start() {
        JDABuilder builder =
                JDABuilder.createLight(
                                properties.botToken(),
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT,
                                GatewayIntent.DIRECT_MESSAGES)
                        .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                        .addEventListeners(this);

        String proxyHost = properties.proxyHost();
        if (proxyHost != null) {
            try {
                WebSocketFactory wsFactory = new WebSocketFactory();
                wsFactory.getProxySettings().setHost(proxyHost);
                wsFactory.getProxySettings().setPort(properties.proxyPort());
                builder.setWebsocketFactory(wsFactory);
                log.info(
                        "Discord channel '{}': Gateway proxy enabled {}:{}",
                        channelId,
                        proxyHost,
                        properties.proxyPort());
            } catch (Exception e) {
                log.warn(
                        "Discord channel '{}': failed to configure proxy {}:{}, falling back to"
                                + " direct connection",
                        channelId,
                        proxyHost,
                        properties.proxyPort(),
                        e);
            }
        }

        JDA client = builder.build();
        this.jda = client;
        log.info(
                "Discord channel '{}' started (onlyAtReply={})",
                channelId,
                properties.onlyAtReply());
    }

    @Override
    public void stop() {
        retryExecutor.shutdownNow();
        JDA client = jda;
        if (client != null) {
            client.shutdown();
            jda = null;
        }
        log.info("Discord channel '{}' stopped", channelId);
    }

    // -----------------------------------------------------------------
    //  JDA event handlers
    // -----------------------------------------------------------------

    @Override
    public void onReady(ReadyEvent event) {
        log.info(
                "Discord channel '{}' ready: bot={}",
                channelId,
                event.getJDA().getSelfUser().getName());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        User author = event.getAuthor();

        // Ignore bot and webhook messages (loop prevention).
        if (author.isBot() || event.isWebhookMessage()) {
            return;
        }

        JDA client = jda;
        if (client == null) {
            return;
        }

        // BotLoopGuard: per-peer sliding-window throttle.
        String peerKey = author.getId();
        if (!botLoopGuard.allow(peerKey)) {
            log.info(
                    "Discord channel '{}': bot-loop guard tripped for peer={}, ignoring message",
                    channelId,
                    peerKey);
            return;
        }

        net.dv8tion.jda.api.entities.Message message = event.getMessage();

        if (event.isFromGuild()) {
            boolean mentioned = message.getMentions().isMentioned(client.getSelfUser());
            if (properties.onlyAtReply() && !mentioned) {
                log.debug(
                        "Discord channel '{}': guild message from {} not mentioning bot, skipping",
                        channelId,
                        author.getName());
                return;
            }
            String text = stripSelfMention(message, client);
            if (text.isBlank()) {
                return;
            }
            // Guild channel: peer = channel id (for outbound reply targeting), guild = server id.
            knownChannels.put(event.getChannel().getId(), event.getChannel());
            InboundMessage inbound =
                    InboundMessage.builder(
                                    channelId,
                                    Peer.channel(event.getChannel().getId()),
                                    List.of(toMsg(text, author)))
                            .guild(event.getGuild().getId())
                            .senderId(CHANNEL_IDENTITY)
                            .build();
            dispatch(inbound)
                    .subscribe(
                            reply -> {},
                            err ->
                                    log.warn(
                                            "Discord channel '{}': inbound dispatch failed"
                                                    + " guild={}",
                                            channelId,
                                            event.getGuild().getId(),
                                            err));
        } else {
            // DM: always respond; peer = user id.
            String text = message.getContentDisplay();
            if (text.isBlank()) {
                return;
            }
            knownChannels.put(author.getId(), event.getChannel());
            InboundMessage inbound =
                    InboundMessage.builder(
                                    channelId,
                                    Peer.direct(author.getId()),
                                    List.of(toMsg(text, author)))
                            .senderId(CHANNEL_IDENTITY)
                            .build();
            dispatch(inbound)
                    .subscribe(
                            reply -> {},
                            err ->
                                    log.warn(
                                            "Discord channel '{}': DM dispatch failed user={}",
                                            channelId,
                                            author.getId(),
                                            err));
        }
    }

    // -----------------------------------------------------------------
    //  Channel dispatch & deliver
    // -----------------------------------------------------------------

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("DiscordChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message)
                .flatMap(reply -> sendReply(route.outboundAddress(), reply).thenReturn(reply));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        JDA client = jda;
        if (client == null) {
            log.warn("Discord channel '{}': deliver called before JDA is ready", channelId);
            return;
        }
        // Rebuild a live outbound client on each deliver call — JDA reference may have changed
        // since construction. The knownChannels map is shared, so cached targets persist.
        DiscordOutboundClient liveClient =
                new DiscordOutboundClient(channelId, client, knownChannels, retryExecutor);
        liveClient.send(address, messages);
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private Mono<Void> sendReply(OutboundAddress address, Msg reply) {
        if (reply == null) {
            return Mono.empty();
        }
        JDA client = jda;
        if (client == null) {
            return Mono.empty();
        }
        DiscordOutboundClient liveClient =
                new DiscordOutboundClient(channelId, client, knownChannels, retryExecutor);
        liveClient.send(address, List.of(reply));
        return Mono.empty();
    }

    /** Strips the bot's own @-mention ({@code <@id>} and {@code <@!id>}) from the message text. */
    private static String stripSelfMention(
            net.dv8tion.jda.api.entities.Message message, JDA client) {
        String selfId = client.getSelfUser().getId();
        return message.getContentDisplay()
                .replace("<@" + selfId + ">", "")
                .replace("<@!" + selfId + ">", "")
                .trim();
    }

    private static Msg toMsg(String text, User author) {
        return Msg.builder().textContent(text).role(MsgRole.USER).name(author.getName()).build();
    }
}
