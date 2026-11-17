package com.caller

/** A name worth comparing: blank, and the placeholder "PC", say nothing about which device it is. */
internal fun comparableDeviceName(name: String): String =
    name.trim().lowercase().takeUnless { it.isEmpty() || it == "pc" }.orEmpty()

/**
 * Keeps one entry per device, in the original order. Two entries are the same device when they share an id or a name
 * (the same rule the phone list already uses when a device is registered), so linking a device again never adds another
 * row. Of the entries that match, the preferred one wins, else the first.
 */
internal fun <T> keepOnePerDevice(
    items: List<T>,
    id: (T) -> String,
    name: (T) -> String,
    isPreferred: (T) -> Boolean = { false }
): List<T> {
    val keptIds = HashSet<String>()
    val keptNames = HashSet<String>()
    val keptIndexes = HashSet<Int>()
    for (index in items.indices.sortedByDescending { if (isPreferred(items[it])) 1 else 0 }) {
        val itemId = id(items[index]).trim()
        val itemName = comparableDeviceName(name(items[index]))
        if ((itemId.isNotEmpty() && itemId in keptIds) || (itemName.isNotEmpty() && itemName in keptNames)) continue
        if (itemId.isNotEmpty()) keptIds.add(itemId)
        if (itemName.isNotEmpty()) keptNames.add(itemName)
        keptIndexes.add(index)
    }
    return items.filterIndexed { index, _ -> index in keptIndexes }
}

/** Ids of the paired PCs, other than [keepId], that are older copies of the same PC (same name). */
internal fun staleCopyIds(pcs: List<PairedPc>, keepId: String, keepName: String): List<String> {
    val name = comparableDeviceName(keepName)
    if (name.isEmpty()) return emptyList()
    return pcs.filter { it.id != keepId && comparableDeviceName(it.name) == name }.map { it.id }
}

/** The PCs to show, one row per PC, by name. The connected PC, else the most recently paired one, is the row kept. */
internal fun pcsToShow(pcs: List<PairedPc>, connectedId: String?): List<PairedPc> =
    keepOnePerDevice(
        items = pcs.sortedByDescending { it.pairedAtMs },
        id = { it.id },
        name = { it.name },
        isPreferred = { connectedId != null && it.id == connectedId }
    ).sortedBy { it.name }
