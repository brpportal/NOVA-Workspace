package com.example.nova.ai

/**
 * Thrown by provider adapters when the provider answered with a
 * syntactically successful HTTP response that carries no usable model
 * output: no `choices` array, an empty `choices` array, a choice without a
 * message, or a message with neither text nor tool calls.
 *
 * Free router tiers (e.g. OpenRouter free models) do this under load
 * instead of returning an error. It is NOT a task failure by itself: the
 * agent loop treats it as retryable (see AgentLoop) and only fails the
 * task after the retries are exhausted.
 */
class ProviderEmptyResponseException(message: String) : Exception(message)
