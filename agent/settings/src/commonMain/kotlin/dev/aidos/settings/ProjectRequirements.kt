package dev.aidos.settings

import dev.aidos.kernel.AvailabilityReport
import dev.aidos.kernel.PlatformProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Network requirement declared in `[requirements]` of `aidos.toml` (RFC-0049). */
enum class NetworkRequirement { REQUIRED, OPTIONAL, NEVER }

/**
 * Advisory tool and network requirements declared in `aidos.toml` under `[requirements]` (RFC-0049).
 */
data class ProjectRequirements(
    val tools: List<String> = emptyList(),
    val optional: Set<String> = emptySet(),
    val network: NetworkRequirement = NetworkRequirement.OPTIONAL,
)

/** Parses `[requirements]` from `aidos.toml` using [TomlParser]. */
object ProjectRequirementsParser {
    fun parse(tomlContent: String, sourcePath: String = "aidos.toml"): ProjectRequirements {
        val parsed = TomlParser.parse(tomlContent, sourcePath)
        val tools = (parsed.values["requirements.tools"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()
        val optional = (parsed.values["requirements.optional"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet()
            ?: emptySet()
        val networkRaw = (parsed.values["requirements.network"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.uppercase()
        val network = runCatching { networkRaw?.let { NetworkRequirement.valueOf(it) } }
            .getOrNull()
            ?: NetworkRequirement.OPTIONAL
        return ProjectRequirements(tools = tools, optional = optional, network = network)
    }
}

/**
 * Evaluates [ProjectRequirements] against the current [PlatformProfile], connectivity, and
 * sidecar state to produce an [AvailabilityReport] at project open (RFC-0049).
 */
object AvailabilityEvaluator {
    fun defaultToolFamiliesFor(
        profile: PlatformProfile,
        networkAvailable: Boolean,
        sidecarAvailable: Boolean = false,
    ): Set<String> = buildSet {
        add("fs")
        add("git")
        if (profile != PlatformProfile.MOBILE) {
            add("shell")
            add("subprocess")
            add("mcp-stdio")
        }
        if (networkAvailable) {
            add("network")
            add("web")
            add("mcp-http")
            add("remote-model")
        }
        if (profile != PlatformProfile.MOBILE || sidecarAvailable) {
            add("local-model")
        }
    }

    fun evaluate(
        requirements: ProjectRequirements,
        profile: PlatformProfile,
        networkAvailable: Boolean,
        sidecarAvailable: Boolean = false,
        availableToolFamilies: Set<String> = defaultToolFamiliesFor(profile, networkAvailable, sidecarAvailable),
    ): AvailabilityReport {
        val satisfied = mutableListOf<String>()
        val degraded = mutableListOf<String>()
        val unsatisfied = mutableListOf<String>()

        for (tool in requirements.tools) {
            when {
                tool in availableToolFamilies -> satisfied += tool
                tool in requirements.optional -> degraded += tool
                else -> unsatisfied += tool
            }
        }

        if (!networkAvailable) {
            when (requirements.network) {
                NetworkRequirement.REQUIRED -> if ("network" !in unsatisfied) unsatisfied += "network"
                NetworkRequirement.OPTIONAL -> if ("network" !in degraded && "network" in requirements.tools) {
                    // already recorded via tools if listed
                }
                NetworkRequirement.NEVER -> Unit
            }
        }

        return AvailabilityReport(
            profile = profile,
            networkAvailable = networkAvailable,
            satisfied = satisfied,
            degraded = degraded,
            unsatisfied = unsatisfied,
        )
    }
}
