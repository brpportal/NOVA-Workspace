package com.example.nova.agent

import com.example.nova.ai.AIToolDefinition
import org.json.JSONObject

data class ToolValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val parsedArguments: JSONObject? = null
)

class ToolRegistry(
    val availableTools: List<AIToolDefinition> = ToolDefinitions.ALL_TOOLS
) {
    private val toolMap = availableTools.associateBy { it.name }

    fun getTool(name: String): AIToolDefinition? = toolMap[name]

    fun isRegistered(name: String): Boolean = toolMap.containsKey(name)

    /**
     * Validates that the tool exists and the arguments JSON can be parsed and satisfies required parameters.
     */
    fun validateCall(toolName: String, argumentsJson: String): ToolValidationResult {
        val tool = getTool(toolName)
            ?: return ToolValidationResult(false, "Unknown tool: '$toolName'")

        val json = try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (e: Exception) {
            return ToolValidationResult(false, "Malformed JSON arguments for tool '$toolName': ${e.message}")
        }

        // Check required fields per schema
        val schema = try { JSONObject(tool.parametersSchemaJson) } catch (e: Exception) { null }
        val required = schema?.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val reqKey = required.getString(i)
                if (!json.has(reqKey) || json.isNull(reqKey)) {
                    return ToolValidationResult(false, "Missing required parameter '$reqKey' for tool '$toolName'")
                }
            }
        }

        return ToolValidationResult(true, parsedArguments = json)
    }

    /**
     * Determines whether the tool execution performs destructive or sensitive actions.
     */
    fun isSensitiveOperation(toolName: String, argumentsJson: String): Boolean {
        return when (toolName) {
            "file_delete" -> true
            "vault_autofill" -> true
            "type" -> {
                // Check if typing into password or payment selector
                val lower = argumentsJson.lowercase()
                lower.contains("password") || lower.contains("card") || lower.contains("cvv") || lower.contains("pay")
            }
            "click" -> {
                val lower = argumentsJson.lowercase()
                lower.contains("submit") || lower.contains("buy") || lower.contains("pay") || lower.contains("order") || lower.contains("delete")
            }
            // "type_text" is deliberately not sensitive: its arguments carry
            // no target-field identity (just an observation index), and a
            // search submit must not stall the task on an approval prompt.
            else -> false
        }
    }
}
