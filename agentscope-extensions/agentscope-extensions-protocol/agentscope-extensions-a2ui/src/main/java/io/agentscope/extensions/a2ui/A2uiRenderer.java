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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelope;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeUtils;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator;
import io.agentscope.extensions.a2ui.envelope.A2uiValidationException;
import io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry;
import java.util.List;
import java.util.Map;

/**
 * Shared render pipeline for {@code a2ui_render} / {@code a2ui_present} /
 * {@code a2ui_ask_user_question}: validate against the catalog, resolve the surface, and serialize
 * the single-layer envelope whose JSON <em>is</em> the tool result text.
 */
public final class A2uiRenderer {

    private final A2uiConfig config;
    private final A2uiCatalog catalog;
    private final A2uiSurfaceRegistry surfaceRegistry;

    public A2uiRenderer(
            A2uiConfig config, A2uiCatalog catalog, A2uiSurfaceRegistry surfaceRegistry) {
        this.config = config;
        this.catalog = catalog;
        this.surfaceRegistry = surfaceRegistry;
    }

    public A2uiCatalog catalog() {
        return catalog;
    }

    /** @return the envelope JSON to be returned as tool result text */
    public String renderEnvelope(
            RuntimeContext runtimeContext, List<Map<String, Object>> components) {
        List<String> errors =
                A2uiEnvelopeValidator.validate(components, catalog, config.maxComponents());
        if (!errors.isEmpty()) {
            throw new A2uiValidationException(String.join(" ", errors));
        }
        A2uiSurfaceRegistry.SurfaceRef surface = surfaceRegistry.resolveOrCreate(runtimeContext);
        A2uiEnvelope envelope =
                new A2uiEnvelope(
                        A2uiConstants.PROTOCOL_VERSION,
                        surface.newlyCreated()
                                ? A2uiConstants.MESSAGE_TYPE_CREATE_SURFACE
                                : A2uiConstants.MESSAGE_TYPE_UPDATE_COMPONENTS,
                        surface.surfaceId(),
                        catalog.catalogId(),
                        components);
        return A2uiEnvelopeUtils.toJson(envelope);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> componentsOf(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return null;
    }
}
