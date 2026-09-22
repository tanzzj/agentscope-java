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

import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.middleware.A2uiPresentStopMiddleware;
import io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry;
import io.agentscope.extensions.a2ui.tool.A2uiAskUserQuestionTool;
import io.agentscope.extensions.a2ui.tool.A2uiCatalogTool;
import io.agentscope.extensions.a2ui.tool.A2uiPresentTool;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.harness.agent.extension.HarnessBuilderExtension;
import io.agentscope.harness.agent.extension.HarnessExtensionContext;

/**
 * A2UI capability as a harness builder extension (see the A2UI spec): attach with
 * {@code HarnessAgent.builder().extension(new A2uiExtension(config))}. Installs the
 * {@code a2ui_catalog} / {@code a2ui_render} / {@code a2ui_present} /
 * {@code a2ui_ask_user_question} tools and the present-stop middleware; surface state persists
 * through the agent's resolved session state store.
 *
 * <p>The catalog resource is loaded at install time — a missing/broken catalog fails the build.
 */
public final class A2uiExtension implements HarnessBuilderExtension {

    private final A2uiConfig config;

    public A2uiExtension() {
        this(A2uiConfig.defaults());
    }

    public A2uiExtension(A2uiConfig config) {
        this.config = config != null ? config : A2uiConfig.defaults();
    }

    @Override
    public void install(HarnessExtensionContext ctx) {
        A2uiCatalog catalog = A2uiCatalog.load(config.catalogResource(), config.catalogId());
        A2uiRenderer renderer =
                new A2uiRenderer(
                        config, catalog, new A2uiSurfaceRegistry(config, ctx.stateStore()));
        ctx.registerTool(new A2uiRenderTool(renderer));
        ctx.registerTool(new A2uiPresentTool(renderer));
        ctx.registerTool(new A2uiCatalogTool(catalog));
        ctx.registerTool(new A2uiAskUserQuestionTool(renderer));
        ctx.addMiddleware(new A2uiPresentStopMiddleware(config));
    }
}
