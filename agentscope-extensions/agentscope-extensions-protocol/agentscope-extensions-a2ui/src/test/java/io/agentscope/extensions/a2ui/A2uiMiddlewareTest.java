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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies {@link A2uiMiddleware} wires the A2UI capability through public framework surfaces
 * only: {@code builder.middleware(...)} plus toolkit rebinding at build time.
 */
class A2uiMiddlewareTest {

    @TempDir Path workspace;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private HarnessAgent build(A2uiMiddleware middleware) throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("a2ui-middleware-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace));
        if (middleware != null) {
            builder.middleware(middleware);
        }
        return builder.build();
    }

    private static List<String> toolNamesOf(HarnessAgent agent) {
        return agent.getDelegate().getToolkit().getToolSchemas().stream()
                .map(ToolSchema::getName)
                .toList();
    }

    @Test
    void attachedMiddlewareRegistersParentToolsWithoutCatalog() throws Exception {
        HarnessAgent agent = build(new A2uiMiddleware());
        List<String> toolNames = toolNamesOf(agent);
        assertTrue(toolNames.contains("a2ui_render"));
        assertTrue(toolNames.contains("ask_user_question"));
        // The component DSL (catalog read + tree submit) lives only on the render sub-agent,
        // and a2ui_present has been merged into a2ui_render.
        assertFalse(toolNames.contains("a2ui_catalog"));
        assertFalse(toolNames.contains("a2ui_present"));
        assertFalse(toolNames.contains("a2ui_ask_user_question"));
    }

    @Test
    void clarificationMiddlewareRegistersPlainAskTool() throws Exception {
        HarnessAgent agent = buildWith(new ClarificationMiddleware());
        List<String> toolNames = toolNamesOf(agent);
        assertTrue(toolNames.contains("ask_user_question"));
        assertFalse(toolNames.contains("a2ui_render"));
    }

    private HarnessAgent buildWith(MiddlewareBase middleware) throws Exception {
        Files.createDirectories(workspace);
        return HarnessAgent.builder()
                .name("a2ui-middleware-test")
                .model(stubModel())
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .middleware(middleware)
                .build();
    }

    @Test
    void withoutMiddlewareNoA2uiTools() throws Exception {
        HarnessAgent agent = build(null);
        List<String> toolNames = toolNamesOf(agent);
        assertFalse(toolNames.contains("a2ui_catalog"));
        assertFalse(toolNames.contains("a2ui_render"));
        assertFalse(toolNames.contains("a2ui_present"));
        assertFalse(toolNames.contains("a2ui_ask_user_question"));
    }

    @Test
    void brokenCatalogFailsFastAtConstruction() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new A2uiMiddleware(
                                A2uiConfig.builder()
                                        .catalogResource("a2ui/does-not-exist.json")
                                        .build()));
    }
}
