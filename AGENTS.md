# AGENTS.md

# Project: Pocket Runtime

## 1. Vision

Build a native Android application that acts as a minimal web
application manager and runtime, with an integrated AI agent for
creating and modifying applications.

Pocket Runtime should make it possible to create, prepare, and execute
browser-compatible web applications directly on an Android phone without
requiring a conventional desktop development environment.

The native Android application is the runtime host and application
manager. Individual applications remain ordinary, independent web
projects.

The product has three central capabilities:

1.  Store and manage multiple local web applications.
2.  Prepare and execute those applications inside Android WebView,
    including resolving browser-compatible npm dependencies.
3.  Let the user create and modify applications through a conversational
    AI agent whose filesystem access is restricted to the selected
    application.

The initial demonstration application is a Twitch chat application using
ComfyJS.

**The main product is Pocket Runtime, not the Twitch application and not
the AI provider.**

## 2. Core architectural principles

Prioritize:

1.  Minimal native Android infrastructure.
2.  On-device application storage, preparation, and execution.
3.  Multiple independent applications.
4.  Reusable, version-aware npm package caching.
5.  A provider-independent agent system.
6.  Strict application-scoped agent filesystem access.
7.  Framework independence.
8.  Direct execution of ordinary browser-compatible web applications.
9.  A simple and understandable runtime architecture.

Do not introduce unnecessary abstractions or infrastructure.

Third-party libraries should be available
through the package system.

The agent is an application-development interface, not an IDE. Do not
build a conventional code editor, terminal, or desktop-style development
environment.

## 3. Technology stack

### 3.1 Native wrapper

-   Kotlin
-   Android SDK
-   Android WebView
-   Android secure storage facilities for secrets
-   Android filesystem APIs

The Kotlin application should remain a minimal runtime host. Use native
functionality where Android requires it, but keep application
preparation, agent orchestration, and other suitable logic in the
JavaScript runtime where practical.

### 3.2 Application execution

-   Browser JavaScript runtime.
-   HTML and CSS rendering through WebView.
-   Native ES modules where possible.
-   Local package resolution.
-   Optional lightweight compilation or bundling only when necessary.

### 3.3 Package ecosystem

-   npm registry metadata.
-   npm package tarballs.
-   Local package storage.
-   Browser-compatible package entry points.

Node.js must not be part of the runtime. Do not require Node.js
installation, npm CLI, a Node.js development server, or a conventional
desktop build pipeline.

The runtime may contain JavaScript tooling when necessary, but it must
execute within the Android application.

### 3.4 AI provider

The first supported provider is Token Harbor.

Use Token Harbor's API through its documented OpenAI-compatible
interface where applicable. Keep provider-specific HTTP details in a
provider adapter, not throughout the agent implementation.

The user chooses from models returned by the configured provider.


## 4. Storage architecture

Pocket Runtime maintains its own local storage structure. The following
is the logical structure; the physical Android paths may differ.

``` text
root/
├── apps/
│   ├── twitch-chat/
│   │   ├── index.html
│   │   ├── app.js
│   │   ├── style.css
│   │   └── packages.json
│   └── another-app/
│       └── ...
├── packages/
│   ├── comfy.js/
│   │   └── 1.0.0/
│   │       ├── package.json
│   │       └── ...
│   └── ...
├── runtime/
│   └── runtime resources and metadata
├── metadata/
│   └── application registry and other runtime metadata
└── agent/
    └── conversation metadata and histories
```

Keep the logical separation consistent even if the implementation uses
Android internal storage, bundled assets, or another physical
arrangement.

### 4.1 Applications

Each application has its own directory under `apps/`.

Application directories are independent workspaces. They must not
contain or control other applications, the shared package store, runtime
source files, provider credentials, or global settings.

Maintain an application registry outside the application directories. It
should contain at least:

-   Stable application ID.
-   Display name.
-   Directory name.
-   Creation timestamp.
-   Optional last-opened timestamp.

The registry is runtime metadata, not an application source file. Do not
require an application manifest to exist immediately after creation.

The application directory name should be generated safely from the
project name, with collision handling. Preserve the user's display name
separately. Reject empty names and names that cannot produce a valid
directory identifier.

### 4.2 Packages

`packages/` contains downloaded and cached npm packages.

Requirements:

-   Packages are shared between applications.
-   Avoid downloading the same package version repeatedly.
-   Support multiple versions of the same package.
-   Store package metadata.
-   Preserve package directory structures where possible.
-   Support dependency relationships.
-   Allow packages to be reused by different applications.
-   Verify package downloads before extraction.
-   Handle interrupted downloads.
-   Avoid corrupting existing cached packages.

The package store must be version-aware.

### 4.3 Runtime and metadata

Keep runtime resources, application registry data, agent histories, and
settings outside application workspaces.

API keys must never be stored in an application directory, agent
conversation, ordinary application preferences, or a file readable by an
application WebView.

## 5. Application manager and dashboard

Replace the original single-application loader screen with a dashboard
of locally registered applications.

### 5.1 Dashboard

The dashboard is the initial view.

Display:

-   Pocket Runtime identity/title.
-   A grid or similarly clear collection of installed applications.
-   An Add App action.
-   A Settings action.

Each application should be represented by a tappable card. Cards may
display the application name and a simple icon or placeholder. Do not
require applications to provide icons.

Interactions:

-   **Tap an application card:** Launch that application through the
    application runner.
-   **Long-press an application card:** Open the agent editing interface
    for that application.
-   **Tap Add App:** Start the empty-project creation flow.
-   **Tap Settings:** Open runtime settings.

Consider that the users screen may turn at any point, and ensure reasonable behavior when doing so.

Do not add a separate dashboard Edit button. Long-press is the editing
gesture.

The dashboard must remain usable on phone-sized screens. Use a
responsive grid or adaptive layout.

### 5.2 Add App flow

Adding an application must initially do only two things:

1.  Ask the user for a project name.
2.  Create an empty application directory and register it in the
    application registry.

Do not generate starter HTML, JavaScript, CSS, package manifests,
framework files, or templates. The new application folder must be empty.

The user will use the agent to create the application contents later.

Validate the name, generate a safe unique directory name, handle
collisions, and report filesystem errors. Do not silently overwrite an
existing application.

### 5.3 Application launch

Launching an application invokes the application runner described below.
Keep the application manager independent of application-specific logic.

The runtime should provide a clear route back to the dashboard and
handle Android back navigation predictably.

## 6. Settings and AI provider configuration

Provide a Settings view reachable from the dashboard.

### 6.1 Initial provider support

Only Token Harbor needs to be supported initially. However, design the
provider boundary so another provider can be added later without
rewriting the agent controller or tools.

Settings should include:

-   Provider selector, initially containing Token Harbor only.
-   API key input with masked display and an explicit reveal/hide
    control.
-   A way to fetch or refresh available models.
-   A model selector populated from the provider's model-list response.
-   Save/apply action.
-   Connection or validation status.
-   Useful error messages for invalid credentials, network failures, and
    unavailable models.

Do not hardcode the model list. Fetch the available models from Token
Harbor using the configured API key and the provider's documented
model-discovery endpoint. Preserve the provider's model identifiers
exactly; display a friendly label when metadata makes one available.

The user intends to use the free DeepSeek V4.1 model route available
through Token Harbor. Do not assume its exact model ID, availability,
price, context size, or capabilities. Discover the current model list at
runtime and let the user select the appropriate returned entry. Do not
silently substitute another model if the selected one is unavailable.

Keep the provider base URL and endpoint construction in the Token Harbor
adapter. Use the current official Token Harbor documentation as the
implementation authority; do not guess endpoint paths or request
schemas.

### 6.2 API key security

Treat the API key as a secret.

-   Store it using Android-protected storage, preferably Android
    Keystore-backed encryption with encrypted local persistence.
-   Never store it in plaintext in ordinary preferences, application
    directories, logs, crash reports, or conversation history.
-   Never expose it to the application WebView.
-   Never expose it to agent tools or model context.
-   Do not include it in error messages.
-   Do not commit it to source control.
-   Mask it in the settings UI by default.
-   Allow the user to replace or clear it.

The agent controller/provider adapter should retrieve the key only when
making an authorized provider request.

### 6.3 Provider API behaviour

Use Token Harbor's documented OpenAI-compatible API where supported.

The provider adapter is responsible for:

-   Authentication.
-   Model discovery.
-   Chat-completion requests.
-   Tool/function calling request formatting.
-   Parsing assistant messages and tool calls.
-   Returning provider errors in a normalized form.

Do not assume every model returned by the provider supports tool
calling. Where possible, inspect model capabilities or handle an
unsupported-tool-call response clearly. The agent needs tool calling to
perform filesystem work. Do not pretend an ordinary text-only response
has executed a tool.

Do not make provider-specific assumptions in the agent controller.

## 7. Agent editing interface

The editing interface is a conversational agent, not an IDE.

Open it when the user long-presses an application card.

The editing view should show:

-   The selected application's display name.
-   A conversation with that application's agent.
-   A text input for requests.
-   A send action.
-   Clear indication when the agent is thinking, using tools, or
    waiting.
-   Tool activity and meaningful progress.
-   Errors and final responses.
-   A way to return to the dashboard.
-   An explicit action to clear the conversation.
-   A back arrow to return to dashboard.

Do not expose a conventional code editor, terminal, or full file
browser. The agent may describe which files it changed. A simple change
summary is useful.

### 7.1 Persistent conversation

Each application has its own persistent agent conversation.

-   Preserve conversation history when leaving the editing view.
-   Reopen the same conversation when the user next edits that
    application.
-   Keep conversations isolated between applications.
-   Store history in runtime-owned metadata, not inside the application
    directory.
-   Do not mix one application's conversation into another application's
    context.
-   Handle application deletion and conversation cleanup consistently.

Provide an explicit **Clear conversation** action. Clearing should
require confirmation because it is destructive.

Clearing the conversation removes that application's stored agent
history and starts a new conversation next time. It must not delete or
modify application files, packages, settings, or the application
registry entry.

Conversation persistence does not mean the entire transcript must be
sent on every request. The agent controller may manage context windows
or summarize older history, but it must preserve relevant project
context and must not silently discard the conversation history stored
locally.

### 7.2 Agent system prompt

The system instructions should identify:

-   The selected application's name and workspace.
-   Prepend before first request to the ai, instructions that they are on a phone and that the development is restricted to certain features.
-   The runtime's supported application conventions.
-   The tools available to it.
-   The strict filesystem boundary.
-   The package installation workflow.
-   How to test the application and inspect runtime errors.
-   That it must not claim a change was made unless the relevant tool
    succeeded.

The prompt is guidance, not the security boundary. Tool implementations
must enforce permissions independently.

## 8. Agent architecture and tool execution

Implement a lightweight, provider-independent tool-calling agent loop.

### 8.1 Agent controller

The agent controller should:

1.  Load the selected application's persistent conversation.
2.  Load the current provider and model settings.
3.  Construct the model request with the relevant conversation and
    available tool definitions.
4.  Send the request through the provider adapter.
5.  Detect tool calls in the model response.
6.  Validate and execute each requested tool.
7.  Append tool results to the conversation.
8.  Continue the model/tool cycle until the model returns a final
    response or a configured safety limit is reached.
9.  Persist conversation updates and tool results.
10. Report failures and allow the user to retry where appropriate.

Support multiple tool-call rounds. Do not assume the model will complete
a task in one response.

Implement sensible limits for maximum tool calls, request size,
timeouts, and cancellation. Avoid infinite loops. Provide a way to stop
an active agent task.

Tool execution must be serialized where operations could conflict,
especially writes, deletes, package installation, and application
preparation.

### 8.2 Initial tools

Provide a deliberately small set of tools:

-   `list_files`: List files and directories inside the selected
    application.
-   `read_file`: Read a text file inside the selected application.
-   `write_file`: Create or replace a text file inside the selected
    application.
-   `delete_file`: Delete a file inside the selected application, with
    safeguards.
-   `search_files`: Search text in application files.
-   `install_package`: Request installation of a package through the
    runtime package manager.
-   `test_app`: Prepare and launch or validate the selected application
    using the runtime.
-   `get_errors`: Retrieve relevant application preparation or execution
    errors.

Tools should have explicit, narrow JSON schemas. Validate every argument
and reject unknown or malformed inputs.

Do not initially provide: - Arbitrary shell execution. - Arbitrary
Kotlin or Android API access. - Unrestricted network access. - General
filesystem access. - Direct access to provider credentials. - Direct
modification of runtime source or settings.

### 8.3 Application-scoped filesystem security

The agent must only access the selected application's directory through
a restricted workspace interface.

Do not rely on a system prompt to enforce this restriction.

Every filesystem tool must:

-   Resolve paths relative to the selected application root.
-   Normalize paths.
-   Reject paths that resolve outside the selected root.
-   Reject access to runtime, metadata, settings, other application
    directories, and shared package storage.
-   Validate file type and size limits.
-   Use safe write semantics where practical.

Use canonical paths or an equivalent robust mechanism. String-prefix
checks alone are not sufficient to establish containment.

The application root must be selected by trusted runtime code, not
supplied or overridden by the model.

The agent may request package installation through `install_package`,
but the runtime performs it using the shared package manager. The agent
must not write into the shared package store.

### 8.4 Destructive operations and backups

Provide safeguards for destructive changes.

At minimum:

-   Create recoverable backups or snapshots before agent modifications.
-   Ensure file writes do not leave partially written files if an
    operation fails.
-   Make deletion explicit and report exactly what was deleted.
-   Consider requiring user confirmation for destructive operations such
    as deleting files or replacing substantial existing content.
-   Keep enough change information to support restoration.


## 9. Application runner

The runner prepares and executes a selected application.

It should:

1.  Locate the selected application's directory using trusted registry
    data.
2.  Read the application's dependency manifest if present.
3.  Resolve dependencies.
4.  Download missing packages.
5.  Build the necessary module-resolution information.
6.  Prepare the application's execution environment.
7.  Load the application's entry point in WebView.
8.  Display execution errors when loading fails.

An empty project is valid as a stored project, but it is not yet
runnable. If it has no entry point, show a clear message and offer the
user the option to open its agent conversation.

### 9.1 Application conventions


A minimal application may contain:

``` text
my-app/
├── index.html
├── app.js
├── style.css
└── packages.json
```

Do not require a complex project structure or framework.

The runner should support direct HTML execution, JavaScript ES modules,
local package imports, browser-compatible npm dependencies, and optional
transformations when required.

### 9.2 Secure local loading

Use a secure local content origin. Investigate Android WebView's
WebViewAssetLoader or an equivalent local content-serving mechanism.

Applications need relative imports, relative stylesheets, local images,
ES modules, and local package resources.

Avoid relying on unrestricted `file://` access.

Restrict WebView navigation and native bridges. Do not expose agent
credentials or privileged runtime functions to application JavaScript.

## 10. npm package manager

Each application may declare dependencies in `packages.json`.

Example:

``` json
{
  "dependencies": {
    "comfy.js": "^1.0.0"
  }
}
```

The runtime should:

1.  Read the application's package manifest.
2.  Resolve package names and versions.
3.  Check the shared package directory.
4.  Download missing packages.
5.  Resolve transitive dependencies.
6.  Make required package modules available to the application.
7.  Prepare the application for execution.

Prefer exact resolved versions after installation. Maintain a lock file
or equivalent resolved dependency record for repeatable loading.

Understand package names, versions, ranges, metadata, dependencies, and
entry points. Inspect `exports`, `browser`, `module`, `main`, and `type`
where relevant.

Prioritize browser-compatible packages. If a package requires Node.js
APIs, native Node modules, or unsupported functionality, report that it
is incompatible.

### 10.1 Package installation security

-   Download from trusted npm registry endpoints.
-   Verify package integrity where metadata provides it.
-   Validate archive paths before extraction.
-   Prevent path traversal.
-   Do not run arbitrary package lifecycle scripts.
-   Avoid corrupting existing cached packages.
-   Keep package permissions restricted.

## 11. Twitch demonstration application

The project has an existing twitch chat demonstration app

## 12. Android wrapper and security

The native wrapper is responsible for:

-   Initializing WebView.
-   Loading the runtime interface.
-   Managing application storage.
-   Starting the runner.
-   Providing controlled local resource access.
-   Managing Android lifecycle events.
-   Handling back navigation.
-   Managing WebView cleanup.
-   Securely storing provider credentials.

Keep the Kotlin implementation small. Avoid unnecessary
dependency-injection frameworks, repository layers, ViewModel
hierarchies, native UI components, networking abstractions, and
application-specific Android services.

Security requirements:

-   Restrict WebView navigation.
-   Avoid unrestricted file access.
-   Do not ignore SSL errors.
-   Avoid unnecessary JavaScript interfaces.
-   Restrict access to native functionality.
-   Keep application resources inside controlled storage.
-   Do not allow arbitrary applications to access unrestricted Android
    APIs.
-   Never expose provider API keys to applications or agent tools.
-   Keep agent filesystem permissions scoped to one selected
    application.
-   Treat model output and tool arguments as untrusted input.

## 13. Build and development philosophy

A major objective is to avoid extensive build infrastructure.

The Android shell may require a minimal Gradle project. User
applications must not require Gradle.

Do not introduce:

-   Mandatory Node.js environment.
-   npm CLI installation.
-   Vite.
-   Webpack.
-   A frontend development server.
-   Desktop compilation pipeline.
-   A separate build process for every application.
-   A full IDE.
-   Arbitrary shell access for the agent.

Prefer a small native project and a self-contained runtime.

The project should be straightforward to build, install, and test on a
physical Android device.

## 14. Development phases

Implement incrementally. Inspect the existing repository before changing
it. Preserve working functionality and avoid rewriting components
without a demonstrated need.

### Phase 1: Multi-application manager

-   Inspect existing loader and storage implementation.
-   Introduce application registry metadata.
-   Support multiple application directories.
-   Implement dashboard grid.
-   Implement tap-to-launch.
-   Implement long-press-to-edit navigation, initially with a
    placeholder editing view.
-   Implement Add App with project-name input and empty-directory
    creation.
-   Implement Settings navigation.

Success criterion: Multiple applications can be registered and launched
independently; a new project creates only an empty directory and
registry entry.

### Phase 2: Settings and Token Harbor

-   Implement secure API-key storage.
-   Implement Token Harbor provider adapter.
-   Implement model discovery.
-   Populate the model selector from the provider response.
-   Allow model selection and persistence.
-   Validate connectivity and show useful errors.
-   Ensure credentials never enter application WebViews or agent
    context.

Success criterion: The user can configure a Token Harbor key, retrieve
available models, select a model, and save the configuration securely.

### Phase 3: Agent interface and persistent conversations

-   Implement the conversational editing view.
-   Create a separate persistent conversation per application.
-   Restore conversations when reopening an application's editor.
-   Implement clear-conversation action with confirmation.
-   Ensure clearing history does not modify project files.
-   Implement loading, tool activity, cancellation, and error states.

Success criterion: Each application has an independent conversation that
persists across navigation and can be explicitly cleared.

### Phase 4: Restricted agent tools

-   Implement the provider-independent agent controller.
-   Implement tool calling and multiple tool rounds.
-   Implement application-scoped filesystem tools.
-   Enforce canonical path containment and reject traversal.
-   Implement controlled package installation.
-   Implement application testing and error retrieval.
-   Add safe write/delete behaviour and backups.

Success criterion: The agent can read and modify only the selected
application, install dependencies through the runtime, test the app, and
diagnose errors.

### Phase 5: Application runner and package manager

If these capabilities are already implemented, verify and preserve them
rather than rebuilding them.

-   Verify application discovery and registry integration.
-   Verify manifest reading.
-   Verify dependency resolution.
-   Verify package downloads and caching.
-   Verify browser-compatible module resolution.
-   Verify secure local WebView loading.
-   Ensure the runner works with multiple application directories.

Success criterion: Independent applications with browser-compatible
dependencies can be prepared and executed.

### Phase 6: Verification

Test:

-   Dashboard layout on a physical Android device.
-   Tap-to-launch and long-press-to-edit.
-   Empty project creation.
-   Duplicate and invalid project names.
-   Multiple independent applications.
-   Token Harbor authentication and model discovery.
-   Model selection and persistence.
-   API key storage and redaction.
-   Persistent conversations and clearing.
-   Agent tool-call loops.
-   Attempts to escape the application directory.
-   Attempts to access other apps, packages, runtime files, and secrets.
-   Interrupted and failed file writes.
-   Package installation and caching.
-   Application execution and runtime error reporting.
-   Offline execution with cached dependencies.
-   Android back navigation and lifecycle.
-   Cancellation and provider/network errors.

## 15. Explicitly out of scope

Do not implement these features unless separately requested:

-   Built-in code editor.
-   Full IDE.
-   General-purpose terminal.
-   Arbitrary shell execution.
-   Application marketplace.
-   Multiple simultaneous application execution contexts.
-   User accounts.
-   Native Node.js runtime.
-   Full npm CLI compatibility.
-   Native Node.js extensions.
-   Arbitrary Node.js APIs.
-   Complex application permission system.
-   Cloud synchronization.
-   Application publishing.
-   Multiple AI providers in the initial implementation.

Keep the architecture extensible without implementing these prematurely.

## 16. Instructions to the coding agent

Before implementation:

1.  Inspect the existing repository and current implementation.
2.  Read the existing project specification and identify what has
    already been completed.
3.  Identify the current storage layout, loader flow, runner, package
    manager, and WebView integration.
4.  Preserve working functionality.
5.  Determine the smallest changes needed for the next phase.
6.  Verify current Token Harbor API documentation before implementing
    endpoint paths, authentication, model discovery, or tool-call
    schemas.
7.  Verify Android secure-storage options and the existing project's
    Android compatibility.
8.  Identify any architectural conflicts before changing files.

During development:

-   Keep the architecture simple.
-   Avoid unnecessary files and dependencies.
-   Do not introduce frontend frameworks without a demonstrated need.
-   Do not assume Node.js APIs exist.
-   Do not introduce build tooling without a demonstrated need.
-   Keep applications independent of the native wrapper.
-   Keep the agent provider-independent.
-   Enforce filesystem restrictions in tool implementations, not just
    prompts.
-   Keep credentials outside application workspaces and model context.
-   Do not claim a feature is implemented until it has been built and
    tested.
-   Document technical limitations honestly.
-   Provide a concise summary of changed files, implementation
    decisions, and tests performed.

**Primary objective:** Build Pocket Runtime into a minimal Android
application platform that can manage multiple local web applications,
execute them without Node.js or a conventional desktop build pipeline,
and let the user create and modify them through a persistent,
provider-independent AI agent whose tools are strictly restricted to the
selected application's workspace.
