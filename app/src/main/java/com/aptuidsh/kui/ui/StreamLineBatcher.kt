package com.aptuidsh.kui.ui

/**
 * 数据层:流式文本的「行」批处理暂存队列。
 *
 * <p>职责:持有由 UI 层用 TextMeasurer 精确折行后得到的行序列(完整、不丢),
 * 供 UI 层每 1000ms 取一行、900ms 内逐字渲染。折行测量依赖 UI 字体/宽度,
 * 故测量在 UI 层完成,本类只负责"行队列 + 出队游标 + 完整性"。
 *
 * <p>行分两类:
 * - 普通行:纯文本视觉行,UI 逐字渲染;
 * - 整块行:代码围栏(```...```)等不可逐字的内容,出现即整块渲染。
 */
data class StreamLine(val text: String, val instant: Boolean = false)

class StreamLineBatcher {

    private val lines = ArrayList<StreamLine>()
    private var cursor = 0

    /** 用最新折行结果整体替换队列(内容只增不减,游标保持,已取出的行不重复)。 */
    fun setLines(newLines: List<StreamLine>) {
        lines.clear()
        lines.addAll(newLines)
        if (cursor > lines.size) cursor = lines.size
    }

    /** 取当前待渲染的下一行(不移动游标)。 */
    fun peekNext(): StreamLine? = lines.getOrNull(cursor)

    /** 当前行渲染完成,游标前移。 */
    fun advance() {
        if (cursor < lines.size) cursor++
    }

    /** 已渲染完成的普通行数。 */
    fun renderedCount(): Int = cursor

    fun reset() {
        cursor = 0
        lines.clear()
    }
}
