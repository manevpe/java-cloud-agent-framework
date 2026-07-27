# tool-support

Pure utility helpers shared by other tool modules. Not a `ToolBundle`
itself — has no `@Tool` methods and is never referenced by name from
workflow YAML or `Agent#requiredTools()`. It exists purely to be an
`implementation` dependency of other `tools/*` modules.

## What's in it

### `ToolResults.orPlaceholder(String value, String placeholder)`

Returns `value` unchanged unless it is `null` or blank, in which case
`placeholder` is returned instead.

Every `@Tool`-annotated method in this repo that returns a `String` and
could legitimately produce an empty result (an empty file, no diff yet,
...) must route its return value through this method before returning.

**Why this matters:** Spring AI's `DefaultToolCallResultConverter`
serializes a tool's `String` return value via
`JsonHelper.toJson(result, true)`, which forwards a result as-is (without
JSON-quoting it) whenever it is already "valid JSON" per
`JsonMapper.readTree`. Jackson 3's `readTree("")` does not throw, so an
empty string is (incorrectly, from this integration's point of view)
treated as already-valid JSON and forwarded verbatim as the literal empty
string. That empty content then becomes the tool's `FunctionResponse`
data sent to Gemini; on the very next round, `GoogleGenAiChatModel`
rebuilds the conversation history from scratch and calls
`parseJsonToMap("")` on it, which throws `RuntimeException: Failed to
parse JSON: ` (empty input) — failing the entire in-flight tool-calling
round unrecoverably. This is a known, still-open upstream bug in the same
family as [spring-ai#4556](https://github.com/spring-projects/spring-ai/issues/4556)
(Gemini tool-calling choking on empty message/response content).

Since every `@Tool` method's return value is under our control, the
simplest reliable fix is entirely on our side: never let one be blank.

## Dependencies

Deliberately has **zero** dependencies of its own — no `:core`, no
Spring AI. Any tool module needing it adds:

```kotlin
dependencies {
    implementation(project(":tools:tool-support"))
}
```

## Usage example

```java
@Tool(description = "Reads a single file's content from a previously cloned workspace.")
public String readWorkspaceFile(String workspaceId, String path) {
    return ToolResults.orPlaceholder(workspaceClient.readFile(workspaceId, path), "(file is empty)");
}
```
