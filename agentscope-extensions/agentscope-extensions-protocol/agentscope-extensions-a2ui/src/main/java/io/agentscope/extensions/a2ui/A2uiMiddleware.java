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
package io.agentscope.extensions.a2ui;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitAware;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.middleware.A2uiPresentStopMiddleware;
import io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry;
import io.agentscope.extensions.a2ui.tool.A2uiAskUserQuestionTool;
import io.agentscope.extensions.a2ui.tool.A2uiCatalogTool;
import io.agentscope.extensions.a2ui.tool.A2uiPresentTool;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The A2UI capability as a single plain middleware (see the A2UI spec): attach with {@code
 * HarnessAgent.builder().middleware(new A2uiMiddleware(config))} — no framework-side seam.
 *
 * <p>Uses only existing public surfaces: {@link ToolkitAware#rebindToolkit} as the build-time
 * install point (the agent builder deep-copies the toolkit and rebinds every middleware, see the
 * interface javadoc on re-registering contributed tools), {@link #onAgent} to capture the
 * agent-resolved {@link AgentStateStore} lazily for surface persistence, and the lifecycle hooks
 * to extend {@link A2uiPresentStopMiddleware}'s stop/prompt behavior. Registers {@code a2ui_render}
 * / {@code a2ui_present} / {@code a2ui_catalog} / {@code a2ui_ask_user_question}.
 *
 * <p>The catalog resource is loaded in the constructor — a missing/broken catalog fails fast at
 * the call site that builds the middleware. One instance is bound to one agent build; reusing the
 * same instance across agents rebinds per-copy but shares the resolved state store, so prefer one
 * middleware per agent.
 */
public final class A2uiMiddleware implements MiddlewareBase, ToolkitAware {

    private final A2uiConfig config;
    private final A2uiCatalog catalog;
    private final A2uiPresentStopMiddleware presentStopDelegate;
    private final AtomicReference<AgentStateStore> stateStore = new AtomicReference<>();

    public A2uiMiddleware() {
        this(A2uiConfig.defaults());
    }

    public A2uiMiddleware(A2uiConfig config) {
        this.config = config != null ? config : A2uiConfig.defaults();
        this.catalog = A2uiCatalog.load(this.config.catalogResource(), this.config.catalogId());
        this.presentStopDelegate = new A2uiPresentStopMiddleware(this.config);
    }

    @Override
    public void rebindToolkit(Toolkit toolkit) {
        A2uiRenderer renderer =
                new A2uiRenderer(config, catalog, new A2uiSurfaceRegistry(config, stateStore::get));
        toolkit.registerTool(new A2uiRenderTool(renderer));
        toolkit.registerTool(new A2uiPresentTool(renderer));
        toolkit.registerTool(new A2uiCatalogTool(catalog));
        toolkit.registerTool(new A2uiAskUserQuestionTool(renderer));
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (agent instanceof ReActAgent react) {
            stateStore.compareAndSet(null, react.getStateStore());
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return presentStopDelegate.onActing(agent, ctx, input, next);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return presentStopDelegate.onSystemPrompt(agent, ctx, currentPrompt);
    }
}
