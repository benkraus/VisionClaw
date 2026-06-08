import Foundation

// MARK: - Grok Tool Call (parsed from server JSON)

struct GrokFunctionCall {
  let id: String
  let name: String
  let args: [String: Any]
}

struct GrokToolCall {
  let functionCalls: [GrokFunctionCall]

  init?(json: [String: Any]) {
    guard json["type"] as? String == "response.function_call_arguments.done",
          let callId = json["call_id"] as? String,
          let name = json["name"] as? String else {
      return nil
    }
    var args: [String: Any] = [:]
    if let arguments = json["arguments"] as? String,
       let data = arguments.data(using: .utf8),
       let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
      args = object
    }
    self.functionCalls = [GrokFunctionCall(id: callId, name: name, args: args)]
  }
}

// MARK: - Grok Tool Call Cancellation

struct GrokToolCallCancellation {
  let ids: [String]

  init?(json: [String: Any]) {
    guard let cancellation = json["toolCallCancellation"] as? [String: Any],
          let ids = cancellation["ids"] as? [String] else {
      return nil
    }
    self.ids = ids
  }
}

// MARK: - Tool Result

enum ToolResult {
  case success(String)
  case failure(String)

  var responseValue: [String: Any] {
    switch self {
    case .success(let result):
      return ["result": result]
    case .failure(let error):
      return ["error": error]
    }
  }
}

// MARK: - Tool Call Status (for UI)

enum ToolCallStatus: Equatable {
  case idle
  case executing(String)
  case completed(String)
  case failed(String, String)
  case cancelled(String)

  var displayText: String {
    switch self {
    case .idle: return ""
    case .executing(let name): return "Running: \(name)..."
    case .completed(let name): return "Done: \(name)"
    case .failed(let name, let err): return "Failed: \(name) - \(err)"
    case .cancelled(let name): return "Cancelled: \(name)"
    }
  }

  var isActive: Bool {
    if case .executing = self { return true }
    return false
  }
}

// MARK: - Tool Declarations (for Grok setup message)

enum ToolDeclarations {

  static func allDeclarations() -> [[String: Any]] {
    return [execute, displayHUD]
  }

  static let execute: [String: Any] = [
    "type": "function",
    "name": "execute",
    "description": "Your only way to take action. You have no memory, storage, or ability to do anything on your own -- use this tool for everything: sending messages, searching the web, adding to lists, setting reminders, creating notes, research, drafts, scheduling, smart home control, app interactions, or any request that goes beyond answering a question. When in doubt, use this tool.",
    "parameters": [
      "type": "object",
      "properties": [
        "task": [
          "type": "string",
          "description": "Clear, detailed description of what to do. Include all relevant context: names, content, platforms, quantities, etc."
        ]
      ],
      "required": ["task"]
    ] as [String: Any]
  ]

  static let displayHUD: [String: Any] = [
    "type": "function",
    "name": "display_hud",
    "description": "Write a short card to the Ray-Ban Display HUD. Use this for task status, results, confirmations, warnings, step lists, navigation hints, and visual summaries that should remain glanceable.",
    "parameters": [
      "type": "object",
      "properties": [
        "title": [
          "type": "string",
          "description": "Short title, ideally under 24 characters."
        ],
        "body": [
          "type": "string",
          "description": "One or two short lines of display text."
        ],
        "kind": [
          "type": "string",
          "enum": ["status", "result", "warning", "steps", "navigation", "confirmation"],
          "description": "The HUD card category."
        ]
      ],
      "required": ["title", "body"]
    ] as [String: Any]
  ]
}
