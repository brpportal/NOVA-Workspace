package com.example.nova.agent

enum class PipelinePresence(val label: String) {
    READY("Ready"),
    WORKING("Working"),
    COMPOSING("Composing"),
    RESPONDING("Responding"),
    MAKING_SOMETHING("Making something"),
    NEEDS_APPROVAL("Needs approval"),
    PROVIDER_LIMIT_REACHED("Provider limit reached")
}
