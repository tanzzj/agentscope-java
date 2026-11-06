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

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discord outbound message sender. Resolves the target snowflake from an {@link OutboundAddress},
 * splits text at the 2000-character Discord limit, and retries transient send failures with
 * exponential backoff.
 */
public final class DiscordOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordOutboundClient.class);

    /** Discord single-message character limit. */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** Maximum retry attempts for transient send failures (proxy flaps, SSL handshake resets). */
    private static final int MAX_SEND_RETRIES = 3;

    /** Base backoff in milliseconds; actual delay = base * 2^attempt (1.5s / 3s / 6s). */
    private static final long RETRY_BASE_DELAY_MS = 1500L;

    private final String channelId;
    private final JDA jda;
    private final Map<String, MessageChannel> knownChannels;
    private final ScheduledExecutorService retryExecutor;

    /**
     * Creates a new outbound client.
     *
     * @param channelId the logical channel id (for logging)
     * @param jda the JDA client instance
     * @param knownChannels shared cache of resolved snowflake → {@link MessageChannel}
     * @param retryExecutor daemon scheduler for retry backoff
     */
    public DiscordOutboundClient(
            String channelId,
            JDA jda,
            Map<String, MessageChannel> knownChannels,
            ScheduledExecutorService retryExecutor) {
        this.channelId = channelId;
        this.jda = jda;
        this.knownChannels = knownChannels;
        this.retryExecutor = retryExecutor;
    }

    /**
     * Sends each message's text content to the peer identified by {@code address.to()}. Text is
     * concatenated and split at the Discord 2000-character limit before sending.
     */
    public void send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        StringBuilder buffer = new StringBuilder();
        for (Msg msg : messages) {
            String text = msg == null ? null : msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(text);
        }
        if (buffer.isEmpty()) {
            return;
        }
        String targetId = extractTargetId(address.to());
        if (targetId == null) {
            log.warn(
                    "Discord outbound: unparseable peerId '{}' channelId='{}'",
                    address.to(),
                    channelId);
            return;
        }
        for (String chunk : split(buffer.toString())) {
            sendText(targetId, chunk, 0);
        }
    }

    /**
     * Extracts the Discord snowflake from an {@link OutboundAddress#to()} string. The SDK format is
     * {@code "<channelId> <PeerKind>:<peerId>"} (e.g. {@code "discord-DC Bot :CHANNEL:123"}); the
     * real snowflake sits after the last colon. Falls back to the raw string when it is already a
     * pure numeric id.
     */
    static String extractTargetId(String rawTo) {
        if (rawTo == null || rawTo.isBlank()) {
            return null;
        }
        int cut = rawTo.lastIndexOf(':');
        String candidate =
                (cut >= 0 && cut < rawTo.length() - 1)
                        ? rawTo.substring(cut + 1).trim()
                        : rawTo.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        return candidate.chars().allMatch(Character::isDigit) ? candidate : null;
    }

    /**
     * Resolves the target {@link MessageChannel} from cache or JDA lookup, then sends. On failure,
     * retries with exponential backoff up to {@link #MAX_SEND_RETRIES} attempts.
     */
    private void sendText(String targetId, String text, int attempt) {
        MessageChannel cached = knownChannels.get(targetId);
        if (cached != null) {
            cached.sendMessage(text)
                    .queue(ok -> {}, err -> handleSendFailure(targetId, text, attempt, err));
            return;
        }
        TextChannel textChannel = jda.getTextChannelById(targetId);
        if (textChannel != null) {
            knownChannels.put(targetId, textChannel);
            textChannel
                    .sendMessage(text)
                    .queue(ok -> {}, err -> handleSendFailure(targetId, text, attempt, err));
            return;
        }
        User user = jda.getUserById(targetId);
        if (user != null) {
            user.openPrivateChannel()
                    .queue(
                            pc -> {
                                knownChannels.put(targetId, pc);
                                pc.sendMessage(text)
                                        .queue(
                                                ok -> {},
                                                err ->
                                                        handleSendFailure(
                                                                targetId, text, attempt, err));
                            },
                            err -> handleSendFailure(targetId, text, attempt, err));
            return;
        }
        log.warn(
                "Discord outbound: target not found targetId='{}' channelId='{}'",
                targetId,
                channelId);
    }

    private void handleSendFailure(String targetId, String text, int attempt, Throwable err) {
        if (attempt < MAX_SEND_RETRIES) {
            long delayMs = RETRY_BASE_DELAY_MS * (1L << attempt);
            log.warn(
                    "Discord send failed, retrying targetId='{}' attempt={}/{} delayMs={} err={}",
                    targetId,
                    attempt + 1,
                    MAX_SEND_RETRIES,
                    delayMs,
                    err.toString());
            retryExecutor.schedule(
                    () -> sendText(targetId, text, attempt + 1), delayMs, TimeUnit.MILLISECONDS);
        } else {
            log.error(
                    "Discord send failed, retries exhausted targetId='{}' channelId='{}'",
                    targetId,
                    channelId,
                    err);
        }
    }

    /**
     * Splits text at the Discord 2000-character limit, preferring line-break boundaries to avoid
     * mid-word cuts.
     */
    static List<String> split(String text) {
        ArrayList<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > MAX_MESSAGE_LENGTH) {
            int cut = rest.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
            if (cut <= MAX_MESSAGE_LENGTH / 2) {
                cut = MAX_MESSAGE_LENGTH;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        if (!rest.isBlank()) {
            out.add(rest);
        }
        return out;
    }

    /** Creates a daemon single-thread executor for send retries. */
    public static ScheduledExecutorService createRetryExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "discord-send-retry");
                    t.setDaemon(true);
                    return t;
                });
    }
}
