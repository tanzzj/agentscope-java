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
 * Final presentation for the current round (spec §8). Same pipeline as {@code a2ui_render}; on
 * success {@link io.agentscope.extensions.a2ui.middleware.A2uiPresentStopMiddleware} stops the
 * acting loop so the envelope is never paraphrased back into reply text.
 */
public class A2uiPresentTool implements AgentTool {

    private final A2uiRenderer renderer;

    public A2uiPresentTool(A2uiRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String getName() {
        return A2uiConstants.TOOL_PRESENT;
    }

    @Override
    public String getDescription() {
        return "Deliver the FINAL A2UI presentation for the user's current request and end this"
                + " round. Use once the UI is complete; after this call do not restate the UI in"
                + " text. For intermediate/progressive updates use a2ui_render instead.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return A2uiComponentSchema.componentsSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return A2uiComponentSchema.invokeRender(renderer, param, "A2UI present failed: ");
    }
}
