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
package io.agentscope.extensions.a2ui.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.a2ui.A2uiConfig;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stops the acting round right after a successful {@code a2ui_render} tool result so the
 * delivered envelope is never paraphrased back into reply text (spec §8.2). Also appends the A2UI
 * usage constraints to the system prompt (spec §5.4).
 */
public final class A2uiRenderStopMiddleware implements MiddlewareBase {

    private static final String SYSTEM_PROMPT_APPEND =
            "\n\n"
                + "## A2UI structured UI\n"
                + "- Show structured UI with `a2ui_render` — it is the only UI entry; it creates or"
                + " updates the surface from your request. Never hand-write A2UI envelope JSON in"
                + " reply text.\n"
                + "- The tool takes a natural-language `description` of the UI (plus optional"
                + " `context` data it must show); a dedicated render agent builds the component"
                + " tree — you never handle components or props yourself.\n"
                + "- `surfaceId` is managed by the system: never generate, guess, or modify it.\n"
                + "- Collect user input with `ask_user_question` instead of plain-text follow-up"
                + " questions.\n";

    private final A2uiConfig config;

    public A2uiRenderStopMiddleware(A2uiConfig config) {
        this.config = config;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (!config.stopAfterPresent()) {
            return next.apply(input);
        }
        return Flux.defer(
                () -> {
                    AtomicBoolean rendered = new AtomicBoolean();
                    return next.apply(input)
                            .doOnNext(
                                    event -> {
                                        // Parent-toolkit render only: the child render agent runs
                                        // without middleware chains, so its internal tool events
                                        // never reach this hook.
                                        if (event instanceof ToolResultEndEvent end
                                                && A2uiConstants.TOOL_RENDER.equals(
                                                        end.getToolCallName())
                                                && end.getState() == ToolResultState.SUCCESS) {
                                            rendered.set(true);
                                        }
                                    })
                            .concatWith(
                                    Flux.defer(
                                            () ->
                                                    rendered.get()
                                                            ? Flux.just(
                                                                    new RequestStopEvent(
                                                                            "A2UI surface delivered"
                                                                                + " via a2ui_render",
                                                                            GenerateReason
                                                                                    .ACTING_STOP_REQUESTED))
                                                            : Flux.empty()));
                });
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt + SYSTEM_PROMPT_APPEND);
    }
}
