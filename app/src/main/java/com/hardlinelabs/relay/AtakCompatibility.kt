package com.hardlinelabs.relay

import org.meshtastic.proto.LocalConfig

/** Read-only assessment. Missing fields and inaccessible module settings are never a pass. */
object AtakCompatibility {
    data class Finding(val level: String, val text: String)
    fun settings(config: LocalConfig): List<Finding> = buildList {
        val lora = config.lora
        val device = config.device
        if (lora == null) add(Finding("CHECK", "LoRa configuration unavailable.")) else {
            add(Finding(if (lora.tx_enabled) "OK" else "BLOCKED", "Radio transmission ${if (lora.tx_enabled) "enabled" else "disabled"}."))
            add(Finding(if (lora.region.name == "US" && lora.modem_preset.name == "LONG_FAST" && lora.use_preset) "OK" else "BLOCKED",
                "Supported profile: US / LongFast. Current: ${lora.region} / ${lora.modem_preset}."))
            add(Finding(if (lora.hop_limit == 7) "OK" else "BLOCKED", "Configured hops: ${lora.hop_limit}; this version expects 7. More hops permit wider forwarding and consume more airtime."))
            add(Finding(if (lora.frequency_offset == 0f && lora.override_frequency == 0f) "OK" else "CHECK", "Frequency slot ${lora.channel_num}; all teammates need the same RF settings. Relay activation handles its slot; manual overrides/offsets can interfere."))
        }
        if (device == null) add(Finding("CHECK", "Device role and rebroadcast filtering unavailable.")) else {
            val mode = device.rebroadcast_mode.name
            add(Finding(if (mode == "CORE_PORTNUMS_ONLY") "BLOCKED" else if (mode in listOf("KNOWN_ONLY", "ALL_SKIP_DECODING")) "CHECK" else "OK",
                "Rebroadcast filter: $mode. CORE_PORTNUMS_ONLY rejects Hardline's private application packets even when text works; Text transport on every teammate can work around this filter. KNOWN_ONLY can exclude unfamiliar peers."))
            val role = device.role.name
            add(Finding(if (role in listOf("CLIENT", "CLIENT_MUTE", "CLIENT_BASE")) "OK" else "CHECK", "Role: $role. Phone-connected clients need continuous reception and Bluetooth; tracker/sensor sleep and infrastructure roles need review."))
        }
        val power = config.power
        add(Finding(if (power == null) "CHECK" else if (power.is_power_saving) "CHECK" else "OK",
            if (power == null) "Power-saving configuration unavailable." else "Power saving ${if (power.is_power_saving) "enabled: may interrupt Bluetooth/reception" else "disabled"}."))
        val bluetooth = config.bluetooth
        add(Finding(if (bluetooth?.enabled == true) "OK" else "CHECK", "Bluetooth configured ${if (bluetooth?.enabled == true) "enabled" else "disabled or unavailable"}."))
        add(Finding("MANUAL", "Cannot verify teammate keys/settings, relay filtering, RF interference, ATAK plugin loaded state, or all Android background restrictions from here. A real teammate receipt is the functional check."))
        add(Finding("MANUAL", "MQTT/telemetry module configuration is not exposed by the pinned external API. Excess broadcasts/downlinks can consume airtime. Hardline accepts LoRa data only; no internet is required."))
    }
    fun text(findings: List<Finding>): String {
        val blocked = findings.count { it.level == "BLOCKED" }
        val checks = findings.count { it.level == "CHECK" }
        val summary = if (blocked > 0) "$blocked blocking setting(s) found" else if (checks > 0) "$checks item(s) need review" else "No blocker found in readable settings"
        return summary + "\nThis scan changes nothing and does not prove delivery.\n\n" + findings.joinToString("\n\n") { "${it.level} · ${it.text}" }
    }
}
