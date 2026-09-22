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
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.a2ui.middleware.A2uiPresentStopMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Verifies {@link A2uiExtension} installs the A2UI capability through the builder seam. */
class A2uiExtensionTest {

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

    private HarnessAgent build(boolean withA2ui) throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("a2ui-wiring-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace));
        if (withA2ui) {
            builder.extension(new A2uiExtension());
        }
        return builder.build();
    }

    @Test
    void attachingExtensionRegistersFourToolsAndStopMiddleware() throws Exception {
        HarnessAgent agent = build(true);
        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertTrue(toolNames.contains("a2ui_catalog"));
        assertTrue(toolNames.contains("a2ui_render"));
        assertTrue(toolNames.contains("a2ui_present"));
        assertTrue(toolNames.contains("a2ui_ask_user_question"));
        assertTrue(
                agent.getDelegate().getMiddlewares().stream()
                        .anyMatch(A2uiPresentStopMiddleware.class::isInstance));
    }

    @Test
    void withoutExtensionNoA2uiToolsAreRegistered() throws Exception {
        HarnessAgent agent = build(false);
        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertFalse(toolNames.contains("a2ui_catalog"));
        assertFalse(toolNames.contains("a2ui_render"));
        assertFalse(toolNames.contains("a2ui_present"));
        assertFalse(toolNames.contains("a2ui_ask_user_question"));
        assertFalse(
                agent.getDelegate().getMiddlewares().stream()
                        .anyMatch(A2uiPresentStopMiddleware.class::isInstance));
    }

    @Test
    void brokenCatalogFailsTheBuild() throws Exception {
        Files.createDirectories(workspace);
        A2uiConfig broken =
                A2uiConfig.builder().catalogResource("a2ui/does-not-exist.json").build();
        assertThrows(
                IllegalStateException.class,
                () ->
                        HarnessAgent.builder()
                                .name("a2ui-broken-catalog")
                                .model(stubModel())
                                .workspace(workspace)
                                .abstractFilesystem(new LocalFilesystem(workspace))
                                .extension(new A2uiExtension(broken))
                                .build());
    }
}
