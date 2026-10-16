package br.com.expoworld.core

/** Presentation only. Theme changes never alter GPS, exploration or saved routes. */
enum class ExplorerTheme(val key: String, val label: String, val accent: Int, val background: Int,
    val surface: Int, val tinted: Int, val text: Int, val muted: Int, val track: Int,
    val carBody: Int, val carHighlight: Int, val traceDark: Int, val traceLight: Int,
    val light: Boolean = false) {
    GREEN("green", "Verdinho", 0x70E1BA, 0x101C22, 0x1C2C33, 0x183A36, 0xFFFFFF, 0xAFBFC6, 0x2E514D,
        0x007469, 0x128F7E, 0x21404A, 0xC9FFF1),
    BLUE("blue", "Azul", 0x91C7FF, 0x101B2B, 0x1B2D43, 0x153751, 0xFFFFFF, 0xB3C4D8, 0x35566F,
        0x246BB5, 0x4E98E5, 0x203D70, 0xD0EBFF),
    RED("red", "Vermelho", 0xFFABA5, 0x261416, 0x3D2327, 0x50272B, 0xFFFFFF, 0xD5BABD, 0x71464A,
        0xB52E37, 0xE35860, 0x6E252D, 0xFFD5CC),
    PINK("pink", "Rosa", 0xF5ABD7, 0x241622, 0x39263A, 0x4B2944, 0xFFFFFF, 0xCFBCD1, 0x6C4665,
        0xC54B94, 0xEB83BD, 0x64284F, 0xFFDAEF),
    YELLOW("yellow", "Amarelo", 0xFFE08C, 0x242014, 0x383221, 0x484022, 0xFFFFFF, 0xCCC6AE, 0x68603B,
        0xD7AD27, 0xF7D55E, 0x64501D, 0xFFF3BA),
    WHITE("white", "Branco", 0x475569, 0xF1F4F7, 0xFFFFFF, 0xE3EAF0, 0x17212B, 0x4E5D6C, 0xBCC9D4,
        0xE3E8EE, 0xFFFFFF, 0x687D8A, 0xFFFFFF, true),
    BLACK("black", "Preto", 0xD1D5DB, 0x09090B, 0x1C1C20, 0x25252A, 0xFFFFFF, 0xB8B8C2, 0x48484F,
        0x252529, 0x53535A, 0x303036, 0xDFDFE5);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: GREEN
        fun hex(rgb: Int) = "#%06X".format(java.util.Locale.ROOT, rgb)
    }
}
