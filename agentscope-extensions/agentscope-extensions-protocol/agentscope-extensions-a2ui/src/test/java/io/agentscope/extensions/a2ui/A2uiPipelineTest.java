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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import io.agentscope.extensions.a2ui.envelope.A2uiEnvelopeValidator;
import io.agentscope.extensions.a2ui.middleware.A2uiPresentStopMiddleware;
import io.agentscope.extensions.a2ui.tool.A2uiAskUserQuestionTool;
import io.agentscope.extensions.a2ui.tool.A2uiRenderTool;
import io.agentscope.extensions.a2ui.tool.A2uiTreeRenderTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Unit coverage for the A2UI pipeline: catalog, validator, surfaces, tools, middleware. */
class A2uiPipelineTest {

    private static final A2uiCatalog CATALOG =
            A2uiCatalog.load("a2ui/basic-catalog.json", "agentscope.io:a2ui/basic");

    private static A2uiRenderer renderer(A2uiConfig config) {
        return new A2uiRenderer(
                config,
                CATALOG,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(config, () -> null),
                () -> null);
    }

    private static A2uiRenderer renderer(A2uiConfig config, ChatModelBase renderModel) {
        return new A2uiRenderer(
                config,
                CATALOG,
                new io.agentscope.extensions.a2ui.state.A2uiSurfaceRegistry(config, () -> null),
                () -> renderModel);
    }

    /** Replays one scripted {@link ChatResponse} per model call; empty text when exhausted. */
    private static final class ScriptedModel extends ChatModelBase {
        private final List<ChatResponse> script;
        private final AtomicInteger idx = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        ScriptedModel(List<ChatResponse> script) {
            this.script = script;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            int i = idx.getAndIncrement();
            return Flux.just(i < script.size() ? script.get(i) : textResponse(""));
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUse(String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name(name)
                                        .input(input)
                                        .content(
                                                io.agentscope.core.util.JsonUtils.getJsonCodec()
                                                        .toJson(input))
                                        .build()))
                .build();
    }

    private static Map<String, Object> comp(
            String id, String component, Map<String, Object> props) {
        return Map.of("id", id, "component", component, "props", props);
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    void builtInCatalogLoadsAllComponentsAndGuideText() {
        assertTrue(CATALOG.componentNames().contains("Heading"));
        assertTrue(CATALOG.componentNames().contains("FileDrop"));
        assertEquals(22, CATALOG.componentNames().size());
        String guide = CATALOG.guideText();
        assertTrue(guide.contains("### Heading"));
        assertTrue(guide.contains("agentscope.io:a2ui/basic"));
    }

    @Test
    void missingCatalogResourceFailsFast() {
        assertThrows(
                IllegalStateException.class,
                () -> A2uiCatalog.load("a2ui/does-not-exist.json", null));
    }

    @Test
    void validatorAcceptsWellFormedComponents() {
        assertTrue(
                A2uiEnvelopeValidator.validate(
                                List.of(
                                        comp("c1", "Heading", Map.of("text", "Hi", "level", 2)),
                                        comp("c2", "Text", Map.of("text", "body"))),
                                CATALOG,
                                50)
                        .isEmpty());
    }

    @Test
    void validatorRejectsUnknownComponent() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("c1", "Spaceship", Map.of())), CATALOG, 50);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("unknown component \"Spaceship\""));
    }

    @Test
    void validatorRejectsMissingRequiredProps() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(comp("c1", "Select", Map.of("name", "q"))), CATALOG, 50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("missing required prop \"options\"")));
    }

    @Test
    void validatorRejectsWrongPropTypeAndEnumViolation() {
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("c1", "Heading", Map.of("text", 42)),
                                comp("c2", "Alert", Map.of("text", "t", "kind", "purple"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("should be string")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be one of")));
    }

    @Test
    void validatorRejectsDuplicateIdsAndEmptyOrNullList() {
        assertTrue(A2uiEnvelopeValidator.validate(List.of(), CATALOG, 50).size() == 1);
        assertTrue(A2uiEnvelopeValidator.validate(null, CATALOG, 50).size() == 1);
        List<String> errors =
                A2uiEnvelopeValidator.validate(
                        List.of(
                                comp("dup", "Text", Map.of("text", "a")),
                                comp("dup", "Text", Map.of("text", "b"))),
                        CATALOG,
                        50);
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate component id")));
    }

    @Test
    void validatorEnforcesMaxComponents() {
        List<Map<String, Object>> many =
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> comp("c" + i, "Text", Map.of("text", "x")))
                        .toList();
        assertFalse(
                A2uiEnvelopeValidator.validate(many, CATALOG, 3).stream()
                        .noneMatch(e -> e.contains("at most 3")));
    }

    @Test
    void firstRenderCreatesSurfaceAndLaterRendersUpdateIt() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-a").build();
        A2uiRenderer renderer = renderer(A2uiConfig.defaults());
        String first =
                renderer.renderEnvelope(rc, List.of(comp("c1", "Text", Map.of("text", "a"))));
        String second =
                renderer.renderEnvelope(rc, List.of(comp("c1", "Text", Map.of("text", "b"))));

        assertTrue(first.contains("\"protocolVersion\":\"1.0\""));
        assertTrue(first.contains("\"messageType\":\"createSurface\""));
        assertTrue(first.contains("\"catalogId\":\"agentscope.io:a2ui/basic\""));
        assertTrue(second.contains("\"messageType\":\"updateComponents\""));

        String surfaceId = first.replaceAll(".*\"surfaceId\":\"(s-[0-9a-f]+)\".*", "$1");
        assertTrue(surfaceId.matches("s-[0-9a-f]{8}"), surfaceId);
        assertTrue(second.contains(surfaceId), "same surface reused within the run");
    }

    @Test
    void treeRenderToolValidatesAndCapturesEnvelope() {
        AtomicReference<String> sink = new AtomicReference<>();
        A2uiTreeRenderTool tool =
                new A2uiTreeRenderTool(renderer(A2uiConfig.defaults()), sink::set);
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").build();

        ToolResultBlock invalid =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(rc)
                                        .input(
                                                Map.of(
                                                        "components",
                                                        List.of(comp("c1", "Nope", Map.of()))))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, invalid.getState());
        assertTrue(textOf(invalid).contains("unknown component"));
        assertTrue(sink.get() == null, "rejected submissions must not reach the sink");

        ToolResultBlock valid =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(rc)
                                        .input(
                                                Map.of(
                                                        "components",
                                                        List.of(
                                                                comp(
                                                                        "c1",
                                                                        "Heading",
                                                                        Map.of(
                                                                                "text", "Hi",
                                                                                "level", 2)))))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, valid.getState());
        assertEquals(textOf(valid), sink.get());
        assertTrue(sink.get().contains("\"messageType\":\"createSurface\""));
    }

    @Test
    void renderToolRunsChildAgentThroughCatalogAndSubmitTurns() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse("t1", "a2ui_catalog", Map.of()),
                                toolUse(
                                        "t2",
                                        "a2ui_render",
                                        Map.of(
                                                "components",
                                                List.of(
                                                        comp(
                                                                "c1",
                                                                "Heading",
                                                                Map.of(
                                                                        "text", "Hi", "level",
                                                                        2))))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Show a greeting"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, result.getState(), textOf(result));
        assertTrue(textOf(result).contains("\"messageType\":\"createSurface\""));
        assertEquals(3, child.calls.get());
    }

    @Test
    void childAgentSelfCorrectsValidationErrorsInsideItsLoop() {
        ScriptedModel child =
                new ScriptedModel(
                        List.of(
                                toolUse("t1", "a2ui_catalog", Map.of()),
                                toolUse(
                                        "t2",
                                        "a2ui_render",
                                        Map.of(
                                                "components",
                                                List.of(comp("c1", "Nope", Map.of())))),
                                toolUse(
                                        "t3",
                                        "a2ui_render",
                                        Map.of(
                                                "components",
                                                List.of(comp("c1", "Text", Map.of("text", "ok"))))),
                                textResponse("done")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Anything"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.RUNNING, result.getState(), textOf(result));
        assertTrue(textOf(result).contains("\"messageType\":\"createSurface\""));
        assertEquals(4, child.calls.get());
    }

    @Test
    void renderToolFailsWhenChildNeverSubmitsValidTree() {
        ScriptedModel child = new ScriptedModel(List.of(textResponse("I can't design that")));
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults(), child));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder().sessionId("s").build())
                                        .input(Map.of("description", "Whatever"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("ended without an accepted component submission"));
    }

    @Test
    void renderToolRequiresDescription() {
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults()));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(RuntimeContext.empty())
                                        .input(Map.of())
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("`description` must be a non-blank"));
    }

    @Test
    void renderToolFailsFastWithoutRenderModel() {
        A2uiRenderTool tool = new A2uiRenderTool(renderer(A2uiConfig.defaults()));
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(RuntimeContext.empty())
                                        .input(Map.of("description", "Anything"))
                                        .build())
                        .block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("no render model available"));
    }

    @Test
    void askUserQuestionSuspendsWithFormEnvelope() {
        A2uiAskUserQuestionTool tool = new A2uiAskUserQuestionTool(renderer(A2uiConfig.defaults()));
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.builder().sessionId("s").build())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "q1",
                                                        "question", "Pick one",
                                                        "type", "select",
                                                        "options", List.of("a", "b")))))
                        .build();

        ToolSuspendException suspend =
                assertThrows(ToolSuspendException.class, () -> tool.callAsync(param).block());
        String envelope = suspend.getReason();
        assertTrue(envelope.contains("\"messageType\":\"createSurface\""));
        assertTrue(envelope.contains("\"component\":\"Select\""));
        assertTrue(envelope.contains("\"name\":\"q1\""));
        assertTrue(envelope.contains("\"action\":\"submit\""));
    }

    @Test
    void askUserQuestionRejectsInvalidQuestions() {
        A2uiAskUserQuestionTool tool = new A2uiAskUserQuestionTool(renderer(A2uiConfig.defaults()));
        ToolCallParam param =
                ToolCallParam.builder()
                        .runtimeContext(RuntimeContext.empty())
                        .input(
                                Map.of(
                                        "questions",
                                        List.of(
                                                Map.of(
                                                        "id", "q1",
                                                        "question", "Pick",
                                                        "type", "select"))))
                        .build();
        ToolResultBlock result = tool.callAsync(param).block();
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("requires non-empty `options`"));
    }

    @Test
    void successfulPresentEmitsStopEvent() {
        A2uiPresentStopMiddleware mw = new A2uiPresentStopMiddleware(A2uiConfig.defaults());
        ToolResultEndEvent presentEnd =
                new ToolResultEndEvent("r1", "tc1", "a2ui_present", ToolResultState.SUCCESS);
        List<AgentEvent> events =
                mw.onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of()),
                                in -> Flux.just(presentEnd))
                        .collectList()
                        .block();
        assertEquals(2, events.size());
        assertInstanceOf(RequestStopEvent.class, events.get(1));
    }

    @Test
    void otherToolResultsDoNotStop() {
        A2uiPresentStopMiddleware mw = new A2uiPresentStopMiddleware(A2uiConfig.defaults());
        ToolResultEndEvent renderEnd =
                new ToolResultEndEvent("r1", "tc1", "a2ui_render", ToolResultState.SUCCESS);
        ToolResultEndEvent presentError =
                new ToolResultEndEvent("r1", "tc2", "a2ui_present", ToolResultState.ERROR);
        List<AgentEvent> events =
                mw.onActing(
                                null,
                                RuntimeContext.empty(),
                                new ActingInput(List.of()),
                                in -> Flux.just(renderEnd, presentError))
                        .collectList()
                        .block();
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(e -> e instanceof RequestStopEvent));
    }

    @Test
    void systemPromptAppendCarriesUsageConstraints() {
        A2uiPresentStopMiddleware mw = new A2uiPresentStopMiddleware(A2uiConfig.defaults());
        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE").block();
        assertTrue(prompt.startsWith("BASE"));
        assertTrue(prompt.contains("a2ui_render"));
        assertTrue(prompt.contains("`surfaceId` is managed by the system"));
    }
}
