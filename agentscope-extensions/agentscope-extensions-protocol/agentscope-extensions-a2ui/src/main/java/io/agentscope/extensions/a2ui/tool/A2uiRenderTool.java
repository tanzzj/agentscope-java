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
package io.agentscope.extensions.a2ui.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.a2ui.A2uiRenderer;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Displays a structured UI: the LLM submits a component tree, the server validates it against the
 * catalog and returns the A2UI envelope JSON as the tool result text (spec §5).
 */
public class A2uiRenderTool implements AgentTool {

    private final A2uiRenderer renderer;

    public A2uiRenderTool(A2uiRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String getName() {
        return A2uiConstants.TOOL_RENDER;
    }

    @Override
    public String getDescription() {
        return "Render (create or update) a structured A2UI surface from a component tree. Call"
                + " a2ui_catalog first to obtain the component catalog. Never hand-write A2UI JSON"
                + " in reply text — always submit it here.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return A2uiComponentSchema.componentsSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return A2uiComponentSchema.invokeRender(renderer, param, "A2UI render failed: ");
    }
}
