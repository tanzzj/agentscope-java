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
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitAware;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.middleware.A2uiRenderStopMiddleware;
import io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.extensions.a2ui.tool.AskUserQuestionTool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The A2UI capability as a single plain middleware (see the A2UI spec): attach with {@code
 * HarnessAgent.builder().middleware(new A2uiMiddleware(config))} — no framework-side seam.
 *
 * <p>Registers only the parent-facing tools {@code a2ui_render} (natural-language intent — the
 * single UI entry, also carrying the old {@code a2ui_present} "final delivery" semantics via
 * {@link A2uiConfig#stopAfterPresent()}) and {@code ask_user_question} with its A2UI form
 * flavour ({@code enableA2ui=true}; the plain clarification flavour lives on {@link
 * ClarificationMiddleware} and is independent of this toggle). The component DSL itself is
 * quarantined in the {@link A2uiRenderManager} sub-agent, which owns the {@code a2ui_catalog}
 * read tool and the internal tree-submit tool on its own throwaway toolkit — the main model
 * never sees the catalog.
 *
 * <p>Uses only existing public surfaces: {@link ToolkitAware#rebindToolkit} as the build-time
 * install point (the agent builder deep-copies the toolkit and rebinds every middleware, see the
 * interface javadoc on re-registering contributed tools), {@link #onAgent} to capture the
 * agent-resolved {@link AgentStateStore} and model lazily, and the lifecycle hooks to extend
 * {@link A2uiRenderStopMiddleware}'s stop/prompt behavior.
 *
 * <p>The render model is the one passed to {@link #A2uiMiddleware(A2uiConfig, Model)} — for a
 * lighter/cheaper UI model than the conversation model — or, when {@code null}, the hosting
 * agent's own model resolved at run time (same fallback shape as the harness memory config).
 *
 * <p>The catalog resource is loaded in the constructor — a missing/broken catalog fails fast at
 * the call site that builds the middleware. One instance is bound to one agent build; reusing the
 * same instance across agents rebinds per-copy but shares the resolved state store, so prefer one
 * middleware per agent.
 */
public final class A2uiMiddleware implements MiddlewareBase, ToolkitAware {

    private final A2uiConfig config;
    private final A2uiCatalog catalog;
    private final A2uiRenderStopMiddleware renderStopDelegate;
    private final AtomicReference<AgentStateStore> stateStore = new AtomicReference<>();
    private final AtomicReference<Model> renderModel = new AtomicReference<>();

    public A2uiMiddleware() {
        this(A2uiConfig.defaults());
    }

    public A2uiMiddleware(A2uiConfig config) {
        this(config, null);
    }

    /**
     * @param renderModel model backing the render sub-agent; {@code null} falls back to the host
     *     agent's own model at run time.
     */
    public A2uiMiddleware(A2uiConfig config, Model renderModel) {
        this.config = config != null ? config : A2uiConfig.defaults();
        this.catalog = A2uiCatalog.load(this.config.catalogResource(), this.config.catalogId());
        this.renderStopDelegate = new A2uiRenderStopMiddleware(this.config);
        this.renderModel.set(renderModel);
    }

    @Override
    public void rebindToolkit(Toolkit toolkit) {
        A2uiRenderer renderer =
                new A2uiRenderer(
                        config,
                        catalog,
                        new A2uiSurfaceRegistry(config, stateStore::get),
                        renderModel::get);
        toolkit.registerTool(new A2uiRenderTool(renderer));
        toolkit.registerTool(new AskUserQuestionTool(renderer, true));
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (agent instanceof ReActAgent react) {
            stateStore.compareAndSet(null, react.getStateStore());
            renderModel.compareAndSet(null, react.getModel());
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return renderStopDelegate.onActing(agent, ctx, input, next);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return renderStopDelegate.onSystemPrompt(agent, ctx, currentPrompt);
    }
}
