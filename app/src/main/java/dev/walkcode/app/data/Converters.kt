package dev.walkcode.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun toShellKind(value: String): ShellKind = ShellKind.valueOf(value)
    @TypeConverter fun fromShellKind(value: ShellKind): String = value.name
}
