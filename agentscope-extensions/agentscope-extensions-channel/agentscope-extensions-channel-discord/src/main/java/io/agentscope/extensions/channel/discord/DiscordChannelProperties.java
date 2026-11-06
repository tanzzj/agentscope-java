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

import java.util.Map;
import java.util.Objects;

/**
 * Provider-specific configuration for a single Discord channel instance, sourced from the {@code
 * properties} block of {@code channels.<id>} in {@code agentscope.json}.
 *
 * @param botToken Discord bot token used to connect to the Gateway via JDA. Required.
 * @param onlyAtReply when {@code true} (default), guild messages are only processed when the bot is
 *     explicitly @-mentioned; DMs are always responded to.
 * @param proxyHost optional HTTP CONNECT proxy host for the Gateway WebSocket (e.g. {@code
 *     "127.0.0.1"} for local clash). JDA's nv-websocket-client does not honour {@code
 *     -Dhttps.proxyHost}.
 * @param proxyPort optional proxy port; defaults to {@code 7890} when {@code proxyHost} is set.
 */
public record DiscordChannelProperties(
        String botToken, boolean onlyAtReply, String proxyHost, int proxyPort) {

    public DiscordChannelProperties {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("discord.botToken is required");
        }
        if (proxyHost != null) {
            proxyHost = proxyHost.trim();
            if (proxyHost.isBlank()) {
                proxyHost = null;
            }
        }
        if (proxyPort <= 0) {
            proxyPort = 7890;
        }
    }

    /** Reads a {@link DiscordChannelProperties} from an arbitrary properties map. */
    public static DiscordChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        return new DiscordChannelProperties(
                asString(p, "botToken"),
                asBooleanOr(p, "onlyAtReply", true),
                asString(p, "proxyHost"),
                asIntOr(p, "proxyPort", 7890));
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean asBooleanOr(Map<String, Object> p, String key, boolean fallback) {
        Object v = p.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    private static int asIntOr(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
