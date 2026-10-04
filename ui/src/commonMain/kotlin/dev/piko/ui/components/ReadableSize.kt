package dev.piko.ui.components

import dev.piko.shared.data.readableSize

fun Long.toReadableSize(): String = readableSize(this)
