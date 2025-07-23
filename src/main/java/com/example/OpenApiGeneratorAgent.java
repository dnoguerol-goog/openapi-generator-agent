package com.example;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;

import io.reactivex.rxjava3.core.Flowable;

public class OpenApiGeneratorAgent {
    private static final Logger ADK_LOGGER = Logger.getLogger(OpenApiGeneratorAgent.class.getName());

    InMemoryRunner runner;

    public static BaseAgent ROOT_AGENT = initAgent();

    public static void main(String[] args) {
        runInteractiveSession();
    }
 
    public static BaseAgent initAgent() {
        return LlmAgent.builder()
            .name("openapi-generator")
            .description("An assistant that can generate a compliant OpenAPI definitions from API specifications.")
            .model("gemini-2.5-flash")
            .instruction("You are an expert API designer that generates OpenAPI-compliant YAML files from an API description. Create an OpenAPI definition YAML from the API description provided by the user, check it for errors using the tool, make any necessary changes to eliminate errors, and return the final output to the user.")
            .tools(getTools())
            .build();
    }

    private static ArrayList<BaseTool> getTools() {
        ArrayList<BaseTool> tools = new ArrayList<>();
        String mcpServerUrl = "http://localhost:8081/sse";
        SseServerParameters params = SseServerParameters.builder().url(mcpServerUrl).build();
        try {
            McpToolset.McpToolsAndToolsetResult mcpResults = McpToolset.fromServer(params, new ObjectMapper()).get();
            tools = mcpResults.getTools().stream().map(mcpTool -> (BaseTool) mcpTool).collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return tools;
    }

    private static void runInteractiveSession() {
        InMemoryRunner runner = new InMemoryRunner(ROOT_AGENT);
        Session session = runner.sessionService().createSession(ROOT_AGENT.name(), "tmp-user",
                (ConcurrentMap<String, Object>) null, (String) null).blockingGet();

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine();

                if ("quit".equalsIgnoreCase(userInput.trim())) {
                    break;
                }
                if (userInput.trim().isEmpty()) {
                    continue;
                }

                Content userMsgForHistory = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events = runner.runWithSessionId(session.id(), userMsgForHistory,
                        RunConfig.builder().build());

                System.out.print("\nAgent > ");
                final StringBuilder agentResponseBuilder = new StringBuilder();
                final AtomicBoolean toolCalledInTurn = new AtomicBoolean(false);
                final AtomicBoolean toolErroredInTurn = new AtomicBoolean(false);

                events.blockingForEach(event -> processAgentEvent(event, agentResponseBuilder,
                        toolCalledInTurn, toolErroredInTurn));

                System.out.println();

                if (toolCalledInTurn.get() && !toolErroredInTurn.get()
                        && agentResponseBuilder.length() == 0) {
                    ADK_LOGGER.warning("Agent used a tool but provided no text response.");
                } else if (toolErroredInTurn.get()) {
                    ADK_LOGGER.warning(
                            "An error occurred during tool execution or in the agent's response processing.");
                }
            }
        }
        System.out.println("Exiting agent.");
    }
        
    private static void processAgentEvent(Event event, StringBuilder agentResponseBuilder,
            AtomicBoolean toolCalledInTurn, AtomicBoolean toolErroredInTurn) {
        if (event.content().isPresent()) {
            event.content().get().parts().ifPresent(parts -> {
                for (Part part : parts) {
                    if (part.text().isPresent()) {
                        System.out.print(part.text().get());
                        agentResponseBuilder.append(part.text().get());
                    }
                    if (part.functionCall().isPresent()) {
                        toolCalledInTurn.set(true);
                    }
                    if (part.functionResponse().isPresent()) {
                        FunctionResponse fr = part.functionResponse().get();
                        fr.response().ifPresent(responseMap -> {
                            if (responseMap.containsKey("error")
                                    || (responseMap.containsKey("status")
                                            && "error".equalsIgnoreCase(
                                                    String.valueOf(responseMap.get("status"))))) {
                                toolErroredInTurn.set(true);
                            }
                        });
                    }
                }
            });
        }
        if (event.errorCode().isPresent() || event.errorMessage().isPresent()) {
            toolErroredInTurn.set(true);
        }
    }

}
