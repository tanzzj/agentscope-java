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

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;

/**
 * The controlled surface a {@link HarnessBuilderExtension} receives at install time. Deliberately
 * minimal: extensions contribute tools and middlewares and read the resolved session state store.
 * New accessors may be added over time; none will be removed lightly.
 */
public interface HarnessExtensionContext {

    /** Registers a tool on the agent's (per-build copied) toolkit. */
    void registerTool(AgentTool tool);

    /** Appends a middleware to the agent's middleware chain. */
    void addMiddleware(MiddlewareBase middleware);

    /** The state store the built agent persists session state through (never null). */
    AgentStateStore stateStore();
}
