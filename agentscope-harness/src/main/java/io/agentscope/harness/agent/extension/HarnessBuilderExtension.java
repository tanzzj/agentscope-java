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
package io.agentscope.harness.agent.extension;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * A self-contained capability that wires itself into a {@link HarnessAgent} at build time, so new
 * subsystems (RAG, custom toolsets, ...) never need a dedicated {@code Builder} method or an edit
 * to the harness orchestration. Attach via {@code HarnessAgent.Builder.extension(...)}.
 *
 * <p>{@link #install(HarnessExtensionContext)} is invoked once, in registration order, at a fixed
 * point of {@code build()}: after built-in workspace/memory tools are registered and after the
 * state store is resolved, but before the inner ReActAgent is assembled. Throw from install to
 * fail the build (e.g. missing configuration).
 */
public interface HarnessBuilderExtension {

    void install(HarnessExtensionContext ctx);
}
