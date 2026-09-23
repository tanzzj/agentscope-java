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

/**
 * Configuration for the A2UI capability. Passed to the {@link A2uiMiddleware} constructor
 * (plain-middleware wiring, no builder API on the framework side); all fields carry sensible
 * defaults.
 */
public final class A2uiConfig {

    private final String catalogId;
    private final String catalogResource;
    private final boolean stopAfterPresent;
    private final int maxComponents;
    private final boolean surfacePersistEnabled;
    private final String renderPrompt;
    private final int renderMaxIters;

    private A2uiConfig(Builder builder) {
        this.catalogId = builder.catalogId;
        this.catalogResource = builder.catalogResource;
        this.stopAfterPresent = builder.stopAfterPresent;
        this.maxComponents = builder.maxComponents;
        this.surfacePersistEnabled = builder.surfacePersistEnabled;
        this.renderPrompt = builder.renderPrompt;
        this.renderMaxIters = builder.renderMaxIters;
    }

    public static A2uiConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Identifier declared inside every envelope (e.g. {@code agentscope.io:a2ui/basic}). */
    public String catalogId() {
        return catalogId;
    }

    /** Classpath resource path of the component catalog JSON. */
    public String catalogResource() {
        return catalogResource;
    }

    /** Whether a successful {@code a2ui_present} stops the current acting round. */
    public boolean stopAfterPresent() {
        return stopAfterPresent;
    }

    /** Upper bound on components per envelope. */
    public int maxComponents() {
        return maxComponents;
    }

    /** Whether the surface registry is persisted across runs via {@code AgentStateStore}. */
    public boolean surfacePersistEnabled() {
        return surfacePersistEnabled;
    }

    /** SYSTEM prompt for the render sub-agent; {@code null} = SDK default prompt. */
    public String renderPrompt() {
        return renderPrompt;
    }

    /** ReAct iteration bound of one render sub-agent run. */
    public int renderMaxIters() {
        return renderMaxIters;
    }

    public static final class Builder {

        private String catalogId = "agentscope.io:a2ui/basic";
        private String catalogResource = "a2ui/basic-catalog.json";
        private boolean stopAfterPresent = true;
        private int maxComponents = 50;
        private boolean surfacePersistEnabled = true;
        private String renderPrompt = null;
        private int renderMaxIters = 6;

        private Builder() {}

        public Builder catalogId(String catalogId) {
            this.catalogId = catalogId;
            return this;
        }

        public Builder catalogResource(String catalogResource) {
            this.catalogResource = catalogResource;
            return this;
        }

        public Builder stopAfterPresent(boolean stopAfterPresent) {
            this.stopAfterPresent = stopAfterPresent;
            return this;
        }

        public Builder maxComponents(int maxComponents) {
            this.maxComponents = maxComponents;
            return this;
        }

        public Builder surfacePersistEnabled(boolean surfacePersistEnabled) {
            this.surfacePersistEnabled = surfacePersistEnabled;
            return this;
        }

        public Builder renderPrompt(String renderPrompt) {
            this.renderPrompt = renderPrompt;
            return this;
        }

        public Builder renderMaxIters(int renderMaxIters) {
            this.renderMaxIters = renderMaxIters;
            return this;
        }

        public A2uiConfig build() {
            return new A2uiConfig(this);
        }
    }
}
