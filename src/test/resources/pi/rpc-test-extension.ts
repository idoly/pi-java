import { createAssistantMessageEventStream, getCurrentSystemPrompt, getCurrentTools } from "@earendil-works/pi-ai";

// Only a local deterministic provider and RPC dialog commands; tools belong to Pi.
export default function(pi: any) {
  pi.on("before_agent_start", (event: any) => {
    if (event.prompt.includes("PI_TEST_EXIT")) process.exit(17);
  });
  let releaseWait: (() => void) | undefined;
  pi.registerCommand("test_release", { description: "Release the waiting provider", handler: async () => releaseWait?.() });
  pi.registerCommand("test_handled", { description: "Handle without a run", handler: async () => {} });
  pi.registerCommand("test_independent", {
    description: "Start an independent run",
    handler: async () => { setTimeout(() => pi.sendUserMessage("PI_TEST_WAIT"), 0); }
  });
  pi.registerCommand("test_ui", {
    description: "RPC dialog",
    handler: async (_args: string, ctx: any) => {
      if (!(await ctx.ui.confirm("Approve fixture?", "No external effects"))) throw new Error("Not confirmed");
    }
  });
  pi.registerCommand("test_ui_all", {
    description: "All RPC UI methods",
    handler: async (_args: string, ctx: any) => {
      if (await ctx.ui.select("Choose", ["A", "B"]) !== "B") throw new Error("Bad select");
      if (await ctx.ui.confirm("Confirm", "message") !== false) throw new Error("Bad confirm");
      if (await ctx.ui.input("Input", "placeholder") !== "hello") throw new Error("Bad input");
      if (await ctx.ui.editor("Edit", "prefill") !== "line1\nline2") throw new Error("Bad editor");
      if (await ctx.ui.input("Cancel") !== undefined) throw new Error("Bad cancellation");
      ctx.ui.notify("notice", "info");
      ctx.ui.setStatus("test", "running");
      ctx.ui.setWidget("test", ["line"]);
      ctx.ui.setTitle("test title");
      ctx.ui.setEditorText("editor text");
    }
  });
  pi.registerCommand("test_ui_timeout", {
    description: "Pi handles dialog timeout",
    handler: async (_args: string, ctx: any) => {
      if (await ctx.ui.select("Timed dialog", ["A"], {timeout: 100}) !== undefined) throw new Error("Expected default");
    }
  });
  pi.registerCommand("test_big_history", {
    description: "Large stored entry for RPC response limits",
    handler: async () => { pi.appendEntry("large-fixture", { value: "x".repeat(17 * 1024 * 1024) }); }
  });
  pi.registerProvider("pi-java-test", {
    api: "pi-java-test-api", apiKey: "local-fixture", baseUrl: "http://127.0.0.1",
    models: [{ id: "deterministic", name: "Local fixture", reasoning: false, input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096 }],
    streamSimple(model: any, context: any, options: any) {
      const stream = createAssistantMessageEventStream();
      const message: any = { role: "assistant", content: [], api: model.api, provider: model.provider,
        model: model.id, timestamp: Date.now(), stopReason: "pending",
        usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
      queueMicrotask(async () => {
        try {
          const user = context.messages.findLastIndex((item: any) => item.role === "user");
          const input = context.messages[user]?.content;
          const prompt = typeof input === "string" ? input : (input || []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("");
          const results = context.messages.slice(user + 1).filter((item: any) => item.role === "toolResult");
          stream.push({ type: "start", partial: message });
          if (prompt.includes("PI_TEST_WAIT")) {
            await new Promise<void>(resolve => {
              const finish = () => {
                options?.signal?.removeEventListener("abort", finish);
                releaseWait = undefined;
                resolve();
              };
              releaseWait = finish;
              options?.signal?.addEventListener("abort", finish, { once: true });
              if (options?.signal?.aborted) finish();
            });
          }
          if (options?.signal?.aborted || prompt.includes("PI_TEST_ERROR")) {
            message.stopReason = options?.signal?.aborted ? "aborted" : "error";
            message.errorMessage = "Deterministic fixture failure";
            stream.push({ type: "error", reason: message.stopReason, error: message });
          } else if (results.length === 0 && (prompt.includes("PI_TEST_READ") || prompt.includes("PI_TEST_IMAGE") || prompt.includes("PI_TEST_CODEMODE"))) {
            const codemode = prompt.includes("PI_TEST_CODEMODE");
            const args = codemode ? { code: `
              const texts = await Promise.all([tools.read({path: "first.txt"}), tools.read({path: "second.txt"})]);
              return { count: texts.length, combined: texts.join("|") };
            ` } : { path: prompt.includes("PI_TEST_IMAGE") ? "image.png" : "first.txt" };
            const call = { type: "toolCall", id: "fixture-" + Date.now(), name: codemode ? "codemode" : "read", arguments: {} };
            message.content.push(call);
            stream.push({ type: "toolcall_start", contentIndex: 0, partial: message });
            stream.push({ type: "toolcall_delta", contentIndex: 0, delta: JSON.stringify(args), partial: message });
            call.arguments = args;
            stream.push({ type: "toolcall_end", contentIndex: 0, toolCall: call, partial: message });
            message.stopReason = "toolUse";
            stream.push({ type: "done", reason: "toolUse", message });
          } else {
            const text = prompt.includes("PI_TEST_STARTUP")
              ? JSON.stringify({ systemPrompt: getCurrentSystemPrompt(context.messages),
                  tools: getCurrentTools(context.messages).map(tool => tool.name) })
              : prompt.includes("PI_TEST_DELTAS") ? "x".repeat(3000)
              : results.length ? JSON.stringify(results[results.length - 1].content) : "done: " + prompt;
            const block = { type: "text", text: "" };
            message.content.push(block);
            stream.push({ type: "text_start", contentIndex: 0, partial: message });
            block.text = text;
            for (const delta of prompt.includes("PI_TEST_DELTAS") ? text : [text]) {
              stream.push({ type: "text_delta", contentIndex: 0, delta, partial: message });
            }
            stream.push({ type: "text_end", contentIndex: 0, content: text, partial: message });
            message.stopReason = "stop";
            stream.push({ type: "done", reason: "stop", message });
          }
        } catch (error: any) {
          message.stopReason = "error"; message.errorMessage = error.message;
          stream.push({ type: "error", reason: "error", error: message });
        } finally { stream.end(); }
      });
      return stream;
    }
  });
}
