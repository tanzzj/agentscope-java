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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.extension.HarnessBuilderExtension;
import io.agentscope.harness.agent.extension.HarnessExtensionContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests the generic {@link HarnessBuilderExtension} seam on {@code HarnessAgent.Builder}. */
class HarnessAgentExtensionSeamTest {

    @TempDir Path workspace;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "stub-id",
                                        List.of(TextBlock.builder().text("ok").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        return model;
    }

    private static final class DummyTool implements AgentTool {
        private final String name;

        DummyTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "dummy";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("dummy"));
        }
    }

    @Test
    void extensionsInstallInRegistrationOrderWithStateStore() throws Exception {
        Files.createDirectories(workspace);
        List<String> installOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        HarnessBuilderExtension first =
                ctx -> {
                    installOrder.add("first");
                    assertNotNull(ctx.stateStore(), "state store resolved before install");
                    ctx.registerTool(new DummyTool("ext_first"));
                };
        HarnessBuilderExtension second =
                ctx -> {
                    installOrder.add("second");
                    ctx.registerTool(new DummyTool("ext_second"));
                };

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("extension-seam-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .extension(first)
                        .extension(second)
                        .build();

        assertEquals(List.of("first", "second"), installOrder);
        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertTrue(toolNames.contains("ext_first"));
        assertTrue(toolNames.contains("ext_second"));
    }

    @Test
    void varargsExtensionsRegisterInOrder() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("extension-seam-list-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .extensions(
                                new DummyExtension("a"),
                                new DummyExtension("b"),
                                new DummyExtension("c"))
                        .build();
        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertTrue(toolNames.containsAll(List.of("ext_a", "ext_b", "ext_c")));
    }

    private static final class DummyExtension implements HarnessBuilderExtension {
        private final String tag;

        DummyExtension(String tag) {
            this.tag = tag;
        }

        @Override
        public void install(HarnessExtensionContext ctx) {
            ctx.registerTool(new DummyTool("ext_" + tag));
        }
    }

    @Test
    void middlewareAddedByExtensionReachesChain() throws Exception {
        Files.createDirectories(workspace);
        MiddlewareBase marker = new MiddlewareBase() {};
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("extension-seam-mw-test")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .extension(ctx -> ctx.addMiddleware(marker))
                        .build();
        assertTrue(agent.getDelegate().getMiddlewares().contains(marker));
    }
}
